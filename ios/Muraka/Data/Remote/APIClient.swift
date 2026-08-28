import Foundation

/// Everything the app asks of the Muraka API.
///
/// An `actor`, and that is the design rather than an ambient choice: it serialises token
/// refresh. If four queued uploads all get `401` at once, only one refresh may run
/// refresh tokens are single-use, so two concurrent refreshes mean the second presents an
/// already-consumed token, the server rejects it, and the contributor is signed out for no
/// reason. ``refreshInFlight`` is what lets the other three wait for the first result and
/// then carry on with the new token.
///
/// Only the endpoints a **contributor** may call are here. The verification queue, the map,
/// trends, the CSV export and everything under `/v1/admin` return `403` for this role, so
/// they are absent rather than present-and-unused.
actor APIClient {
    private let baseURL: URL
    private let session: URLSession
    private let tokens: TokenStore
    private let serverClock: ServerClock
    private let sessionEvents: SessionEvents

    private let decoder = JSONDecoder.muraka()
    private let encoder = JSONEncoder.muraka()

    /// The single in-flight refresh, if any. Everything else waits on it.
    private var refreshInFlight: Task<String?, Never>?

    init(
        baseURL: URL = AppConfiguration.baseURL,
        tokens: TokenStore,
        serverClock: ServerClock,
        sessionEvents: SessionEvents,
        session: URLSession = .shared
    ) {
        self.baseURL = baseURL
        self.tokens = tokens
        self.serverClock = serverClock
        self.sessionEvents = sessionEvents
        self.session = session
    }

    // -- Authentication ------------------------------------------------------

    func register(email: String, password: String, displayName: String) async throws -> SessionDTO {
        try await send(
            .post("v1/auth/register", body: RegisterRequest(
                email: email.trimmingCharacters(in: .whitespaces).lowercased(),
                password: password,
                displayName: displayName.trimmingCharacters(in: .whitespaces)
            )),
            authenticated: false
        )
    }

    func login(email: String, password: String) async throws -> SessionDTO {
        try await send(
            .post("v1/auth/login", body: LoginRequest(
                email: email.trimmingCharacters(in: .whitespaces).lowercased(),
                password: password
            )),
            authenticated: false
        )
    }

    func logout(refreshToken: String) async {
        // Best effort: revoking server-side is courteous, but a failure must not keep the
        // contributor signed in against their wish.
        _ = try? await sendIgnoringBody(
            .post("v1/auth/logout", body: RefreshRequest(refreshToken: refreshToken)),
            authenticated: false
        )
    }

    // -- Account -------------------------------------------------------------

    /// The only source of contribution totals. Never count local rows.
    func me() async throws -> MeDTO {
        try await send(.get("v1/me"))
    }

    /// Anonymises rather than erases. NFR15 requires the app to say so before confirming.
    func deleteAccount() async throws {
        try await sendIgnoringBody(.delete("v1/me"))
    }

    // -- Sightings -----------------------------------------------------------

    /// `201` on create, `200` on replay. The client treats them identically - that is the
    /// entire point of it generating the id.
    func createSighting(_ request: CreateSightingRequest) async throws -> SightingDTO {
        try await send(.post("v1/sightings", body: request))
    }

    /// Automatically scoped to the caller's own sightings; pass no contributor filter.
    func listSightings(limit: Int = 50, offset: Int = 0) async throws -> SightingPageDTO {
        try await send(.get("v1/sightings", query: ["limit": "\(limit)", "offset": "\(offset)"]))
    }

    /// The reconciliation primitive.
    ///
    /// Returns nil on `404` - the server has nothing under this id, which is an **answer**
    /// rather than a failure. A `200` carries `photos[]`, whose ids are the client's own, so
    /// diffing gives the exact set still missing.
    func sighting(id: String) async throws -> SightingDetailDTO? {
        do {
            return try await send(.get("v1/sightings/\(id)")) as SightingDetailDTO
        } catch ApiError.notFound {
            return nil
        }
    }

    /// Photograph bytes. Requires the bearer token - this is not a public URL.
    func photoImage(id: String) async throws -> Data {
        try await sendRaw(.get("v1/photos/\(id)/image"))
    }

    // -- Requests ------------------------------------------------------------

    /// One request, described declaratively so ``perform(_:)`` can retry it after a refresh.
    struct Request: Sendable {
        var method: String
        var path: String
        var query: [String: String] = [:]
        var body: Data?
        var contentType: String?

        static func get(_ path: String, query: [String: String] = [:]) -> Request {
            Request(method: "GET", path: path, query: query)
        }

        static func delete(_ path: String) -> Request {
            Request(method: "DELETE", path: path)
        }

        static func post(_ path: String, body: some Encodable) -> Request {
            Request(
                method: "POST",
                path: path,
                body: try? JSONEncoder.muraka().encode(body),
                contentType: "application/json"
            )
        }

        static func upload(_ path: String, body: Data, contentType: String) -> Request {
            Request(method: "POST", path: path, body: body, contentType: contentType)
        }
    }

    func send<Response: Decodable>(_ request: Request, authenticated: Bool = true) async throws -> Response {
        let data = try await perform(request, authenticated: authenticated)
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            // A 200 whose body will not parse is NOT a success. Treating it as one is how a
            // client deletes local files on the strength of something it could not read.
            throw ApiError.unexpected(detail: "could not decode \(Response.self): \(error)")
        }
    }

    @discardableResult
    func sendIgnoringBody(_ request: Request, authenticated: Bool = true) async throws -> Data {
        try await perform(request, authenticated: authenticated)
    }

    func sendRaw(_ request: Request, authenticated: Bool = true) async throws -> Data {
        try await perform(request, authenticated: authenticated)
    }

    /// Sends, and on `401` refreshes **once** and retries.
    private func perform(_ request: Request, authenticated: Bool) async throws -> Data {
        let token = authenticated ? await tokens.current()?.accessToken : nil

        do {
            return try await execute(request, bearer: token)
        } catch ApiError.unauthorized where authenticated {
            // Exactly one refresh, shared with anything else that failed at the same moment.
            guard let refreshed = await refreshOnce(staleToken: token) else {
                throw ApiError.unauthorized
            }
            return try await execute(request, bearer: refreshed)
        }
    }

    private func execute(_ request: Request, bearer: String?) async throws -> Data {
        var components = URLComponents(
            url: baseURL.appendingPathComponent(request.path),
            resolvingAgainstBaseURL: false
        )
        if !request.query.isEmpty {
            // Sorted for determinism: stable URLs in test assertions and in logs.
            components?.queryItems = request.query
                .sorted { $0.key < $1.key }
                .map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = components?.url else {
            throw ApiError.unexpected(detail: "could not build a URL for \(request.path)")
        }

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method
        urlRequest.httpBody = request.body
        urlRequest.timeoutInterval = 30
        if let contentType = request.contentType {
            urlRequest.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        if let bearer {
            urlRequest.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: urlRequest)
        } catch {
            throw ApiError.from(error)
        }

        guard let http = response as? HTTPURLResponse else {
            throw ApiError.unexpected(detail: "non-HTTP response")
        }

        // Free calibration: the header is on every response, and it is what stops a phone
        // with a wrong clock losing the sighting it just captured. See ServerClock.
        if let raw = http.value(forHTTPHeaderField: "Date"), let date = Self.parseHTTPDate(raw) {
            serverClock.observe(serverDate: date)
        }

        guard (200 ..< 300).contains(http.statusCode) else {
            let envelope = try? decoder.decode(ErrorEnvelope.self, from: data)
            let error = ApiError.from(status: http.statusCode, envelope: envelope)
            if case .accountDisabled = error { sessionEvents.send(.accountDisabled) }
            throw error
        }

        return data
    }

    /// The serialised refresh.
    ///
    /// Returns the new access token, or nil when the session is genuinely over.
    private func refreshOnce(staleToken: String?) async -> String? {
        // Somebody else already refreshed while this request was failing. Use what they
        // stored rather than burning a second single-use refresh token.
        if let stored = await tokens.current(), stored.accessToken != staleToken {
            return stored.accessToken
        }

        if let existing = refreshInFlight {
            return await existing.value
        }

        let task = Task<String?, Never> { [tokens, sessionEvents] in
            guard let stored = await tokens.current() else { return nil }

            do {
                let session: SessionDTO = try await self.send(
                    .post("v1/auth/refresh", body: RefreshRequest(refreshToken: stored.refreshToken)),
                    authenticated: false
                )
                // BOTH tokens, in one write. The presented refresh token died the moment
                // the server answered, so losing the new one signs the contributor out.
                await tokens.save(StoredSession(
                    accessToken: session.accessToken,
                    refreshToken: session.refreshToken,
                    expiresAt: session.expiresAt,
                    userID: session.user.id
                ))
                return session.accessToken
            } catch ApiError.unauthorized, ApiError.invalidCredentials {
                // An explicit rejection ends the session. A network failure does NOT - the
                // token may be perfectly valid and simply unreachable.
                await tokens.clear()
                sessionEvents.send(.refreshFailed)
                return nil
            } catch ApiError.accountDisabled {
                await tokens.clear()
                sessionEvents.send(.accountDisabled)
                return nil
            } catch {
                return nil
            }
        }

        refreshInFlight = task
        let result = await task.value
        refreshInFlight = nil
        return result
    }
}

extension APIClient {
    /// RFC 1123, the format of the HTTP `Date` header.
    ///
    /// Built per call rather than held as a static: `DateFormatter` is a class with shared
    /// mutable state and so is not `Sendable`, and no `Sendable` format style covers RFC
    /// 1123. This runs once per response, which is not a hot path, and the alternative
    /// would be an unsafe opt-out for no measurable gain.
    ///
    /// The fixed locale and time zone are not optional: a device in Male must parse a
    /// header written in English and GMT, and a device set to Dhivehi would otherwise
    /// silently fail to - which would leave the clock uncalibrated exactly where the
    /// correction matters most.
    static func parseHTTPDate(_ raw: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter.date(from: raw)
    }
}
