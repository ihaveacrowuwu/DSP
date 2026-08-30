// Package imagemeta reads the two EXIF facts NFR5 names - capture time and GPS
// position - out of an uploaded JPEG.
//
// It exists because the ordering in NFR5 is the whole point: "stripping EXIF
// *after* extracting capture time and GPS". The upload handler strips EXIF by
// re-encoding, which is irreversible, so anything worth keeping has to be read
// off the original bytes first. Until this package existed the strip happened
// and the extraction did not, which satisfied the letter of the privacy claim by
// throwing away the data instead of handling it.
//
// It is written by hand rather than pulled from a library. The backend has five
// direct dependencies and this needs two tags and a rational-number type, not a
// general-purpose metadata toolkit - and a parser reading attacker-controlled
// bytes is exactly the code whose bounds checks are worth being able to read.
//
// Every read is bounds-checked against the segment, offsets are validated before
// use, and the IFD walk is depth- and count-limited, because an EXIF block is
// attacker-controlled input: it arrives in a file a stranger uploaded.
package imagemeta

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"time"
)

// Metadata is what the upload path keeps from an image before re-encoding
// discards the rest. Both fields are optional: most photographs carry a capture
// time, far fewer carry a position, and a file may carry neither.
type Metadata struct {
	CapturedAt *time.Time
	Lat, Lon   *float64
}

const (
	// EXIF tag numbers, from the TIFF/EXIF specification.
	tagExifIFD          = 0x8769
	tagGPSIFD           = 0x8825
	tagDateTimeOriginal = 0x9003
	tagDateTimeDigitize = 0x9004
	tagDateTime         = 0x0132
	tagGPSLatitudeRef   = 0x0001
	tagGPSLatitude      = 0x0002
	tagGPSLongitudeRef  = 0x0003
	tagGPSLongitude     = 0x0004

	typeASCII    = 2
	typeLong     = 4
	typeRational = 5

	// An IFD with thousands of entries is not a photograph's metadata; it is
	// someone seeing how long the parser will keep walking.
	maxEntriesPerIFD = 512
	// APP1 segments are capped at 64 KiB by the JPEG format itself.
	maxExifBytes = 1 << 16
)

var errNoExif = errors.New("imagemeta: no EXIF segment")

// FromJPEG reads capture time and GPS position from a JPEG's EXIF block.
//
// A file with no EXIF, truncated EXIF, or EXIF this parser cannot make sense of
// is not an error the caller should care about: it is the common case, and an
// upload must never fail because its metadata was odd. Errors are returned for
// the caller to log, never to reject on, and the Metadata is always usable.
func FromJPEG(data []byte) (Metadata, error) {
	segment, err := exifSegment(data)
	if err != nil {
		return Metadata{}, err
	}
	return parseExif(segment)
}

// exifSegment walks the JPEG marker chain to the APP1 segment carrying "Exif\0\0".
func exifSegment(data []byte) ([]byte, error) {
	if len(data) < 4 || data[0] != 0xFF || data[1] != 0xD8 {
		return nil, errNoExif
	}

	for i := 2; i+4 <= len(data); {
		if data[i] != 0xFF {
			return nil, errNoExif
		}
		marker := data[i+1]
		// Standalone markers carry no length payload.
		if marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7) {
			i += 2
			continue
		}
		// Start of scan: the entropy-coded image follows and there is no more
		// metadata to find.
		if marker == 0xDA || marker == 0xD9 {
			return nil, errNoExif
		}

		size := int(binary.BigEndian.Uint16(data[i+2 : i+4]))
		if size < 2 || i+2+size > len(data) {
			return nil, errNoExif
		}
		payload := data[i+4 : i+2+size]

		if marker == 0xE1 && len(payload) > 6 && bytes.Equal(payload[:6], []byte("Exif\x00\x00")) {
			body := payload[6:]
			if len(body) > maxExifBytes {
				body = body[:maxExifBytes]
			}
			return body, nil
		}
		i += 2 + size
	}
	return nil, errNoExif
}

// reader is a bounds-checked view over the TIFF block, which is the unit every
// EXIF offset is relative to.
type reader struct {
	buf   []byte
	order binary.ByteOrder
}

func (r reader) u16(off int) (uint16, bool) {
	if off < 0 || off+2 > len(r.buf) {
		return 0, false
	}
	return r.order.Uint16(r.buf[off : off+2]), true
}

func (r reader) u32(off int) (uint32, bool) {
	if off < 0 || off+4 > len(r.buf) {
		return 0, false
	}
	return r.order.Uint32(r.buf[off : off+4]), true
}

func (r reader) slice(off, n int) ([]byte, bool) {
	if off < 0 || n < 0 || off+n > len(r.buf) {
		return nil, false
	}
	return r.buf[off : off+n], true
}

func parseExif(tiff []byte) (Metadata, error) {
	var meta Metadata
	if len(tiff) < 8 {
		return meta, errNoExif
	}

	var order binary.ByteOrder
	switch {
	case tiff[0] == 'I' && tiff[1] == 'I':
		order = binary.LittleEndian
	case tiff[0] == 'M' && tiff[1] == 'M':
		order = binary.BigEndian
	default:
		return meta, errNoExif
	}

	r := reader{buf: tiff, order: order}
	if magic, ok := r.u16(2); !ok || magic != 42 {
		return meta, errNoExif
	}
	first, ok := r.u32(4)
	if !ok {
		return meta, errNoExif
	}

	ifd0 := r.entries(int(first))
	exifOff := ifd0[tagExifIFD].long(r)
	gpsOff := ifd0[tagGPSIFD].long(r)

	// DateTimeOriginal is when the shutter fired; DateTime is when the file was
	// last written, which an edit will have moved. Prefer the former, and treat
	// the others only as fallbacks.
	var exifIFD map[uint16]entry
	if exifOff > 0 {
		exifIFD = r.entries(exifOff)
	}
	for _, candidate := range []struct {
		ifd map[uint16]entry
		tag uint16
	}{
		{exifIFD, tagDateTimeOriginal},
		{exifIFD, tagDateTimeDigitize},
		{ifd0, tagDateTime},
	} {
		if candidate.ifd == nil {
			continue
		}
		if e, found := candidate.ifd[candidate.tag]; found {
			if when, err := parseExifTime(e.ascii(r)); err == nil {
				meta.CapturedAt = &when
				break
			}
		}
	}

	if gpsOff > 0 {
		gps := r.entries(gpsOff)
		lat, latOK := coordinate(r, gps, tagGPSLatitude, tagGPSLatitudeRef, "N", "S", 90)
		lon, lonOK := coordinate(r, gps, tagGPSLongitude, tagGPSLongitudeRef, "E", "W", 180)
		// Both or neither: half a fix is not a position, and a photograph with
		// only a latitude would place every sighting on the prime meridian.
		if latOK && lonOK {
			meta.Lat, meta.Lon = &lat, &lon
		}
	}

	return meta, nil
}

type entry struct {
	typ    uint16
	count  uint32
	offset int // offset of the 4-byte value field within the TIFF block
	ok     bool
}

// entries reads one IFD into a tag-keyed map. An unreadable IFD yields an empty
// map rather than an error: a photograph with damaged metadata is still a
// photograph, and the caller has nothing useful to do with the distinction.
func (r reader) entries(off int) map[uint16]entry {
	out := map[uint16]entry{}
	count, ok := r.u16(off)
	if !ok || count == 0 || count > maxEntriesPerIFD {
		return out
	}
	for i := 0; i < int(count); i++ {
		base := off + 2 + i*12
		tag, tagOK := r.u16(base)
		typ, typOK := r.u16(base + 2)
		n, nOK := r.u32(base + 4)
		if !tagOK || !typOK || !nOK {
			return out
		}
		out[tag] = entry{typ: typ, count: n, offset: base + 8, ok: true}
	}
	return out
}

// long reads an entry whose value is a single offset stored inline.
func (e entry) long(r reader) int {
	if !e.ok || e.typ != typeLong || e.count != 1 {
		return 0
	}
	v, ok := r.u32(e.offset)
	if !ok || v > uint32(len(r.buf)) {
		return 0
	}
	return int(v)
}

// ascii reads a string value, which lives inline when it fits in four bytes and
// behind an offset otherwise.
func (e entry) ascii(r reader) string {
	if !e.ok || e.typ != typeASCII || e.count == 0 || e.count > maxExifBytes {
		return ""
	}
	n := int(e.count)
	start := e.offset
	if n > 4 {
		off, ok := r.u32(e.offset)
		if !ok {
			return ""
		}
		start = int(off)
	}
	raw, ok := r.slice(start, n)
	if !ok {
		return ""
	}
	return string(bytes.TrimRight(raw, "\x00 "))
}

// rationals reads a run of unsigned rationals, the form GPS coordinates take:
// three of them, for degrees, minutes and seconds.
func (e entry) rationals(r reader) []float64 {
	if !e.ok || e.typ != typeRational || e.count == 0 || e.count > 8 {
		return nil
	}
	off, ok := r.u32(e.offset)
	if !ok {
		return nil
	}
	out := make([]float64, 0, e.count)
	for i := 0; i < int(e.count); i++ {
		num, numOK := r.u32(int(off) + i*8)
		den, denOK := r.u32(int(off) + i*8 + 4)
		if !numOK || !denOK || den == 0 {
			return nil
		}
		out = append(out, float64(num)/float64(den))
	}
	return out
}

// coordinate converts one degrees/minutes/seconds triple plus its hemisphere
// reference into a signed decimal degree.
func coordinate(r reader, ifd map[uint16]entry, valueTag, refTag uint16, positive, negative string, limit float64) (float64, bool) {
	dms := ifd[valueTag].rationals(r)
	if len(dms) < 3 {
		return 0, false
	}
	ref := ifd[refTag].ascii(r)

	value := dms[0] + dms[1]/60 + dms[2]/3600
	switch ref {
	case positive:
	case negative:
		value = -value
	default:
		// A missing or unrecognised hemisphere makes the sign a guess, and a
		// guessed sign puts the sighting in the wrong hemisphere.
		return 0, false
	}
	if value < -limit || value > limit {
		return 0, false
	}
	return value, true
}

// parseExifTime reads EXIF's "2026:08:20 08:30:00".
//
// The format carries no zone, and EXIF's optional offset tags are rarely
// populated by the cameras this project sees, so the value is read as UTC. That
// is a documented approximation, not an oversight: the authoritative capture
// time is the one the app sends with the sighting, and this is a cross-check
// for gallery imports where the app has nothing better.
func parseExifTime(s string) (time.Time, error) {
	if s == "" {
		return time.Time{}, fmt.Errorf("imagemeta: empty timestamp")
	}
	when, err := time.Parse("2006:01:02 15:04:05", s)
	if err != nil {
		return time.Time{}, fmt.Errorf("imagemeta: unrecognised timestamp %q: %w", s, err)
	}
	// A camera with a dead clock reports 1980, and a photograph cannot have been
	// taken after it was uploaded. Neither is worth storing.
	if when.Year() < 1990 || when.After(time.Now().UTC().Add(24*time.Hour)) {
		return time.Time{}, fmt.Errorf("imagemeta: implausible capture time %q", s)
	}
	return when.UTC(), nil
}
