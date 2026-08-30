package imagemeta

import (
	"bytes"
	"encoding/binary"
	"testing"
	"time"
)

// The fixtures are built rather than checked in, so the bytes a test depends on
// are visible in the test. A binary .jpg in the repository would make "why does
// this assert 4.05?" unanswerable without a hex editor.

type ifdEntry struct {
	tag   uint16
	typ   uint16
	count uint32
	value []byte // exactly 4 bytes: an inline value or an offset
}

func inline32(v uint32) []byte {
	b := make([]byte, 4)
	binary.LittleEndian.PutUint32(b, v)
	return b
}

// buildJPEG wraps a TIFF block in the APP1 segment of an otherwise minimal JPEG.
func buildJPEG(tiff []byte) []byte {
	var out bytes.Buffer
	out.Write([]byte{0xFF, 0xD8}) // SOI

	payload := append([]byte("Exif\x00\x00"), tiff...)
	out.Write([]byte{0xFF, 0xE1})
	binary.Write(&out, binary.BigEndian, uint16(len(payload)+2))
	out.Write(payload)

	out.Write([]byte{0xFF, 0xD9}) // EOI
	return out.Bytes()
}

func ifdLen(n int) int { return 2 + n*12 + 4 }

// layout computes where each IFD and the trailing value block will land, given
// only how many entries each IFD will hold.
//
// It is computed rather than patched afterwards. An earlier version of this file
// searched the assembled bytes for a tag number and rewrote the four bytes after
// it, which silently matched a *type* field instead - type 2 (ASCII) and tag
// 0x0002 (GPSLatitude) have identical little-endian bytes - and produced
// fixtures that pointed at nothing. A test helper that can build the wrong
// fixture is worse than no helper.
func layout(nIFD0, nExif, nGPS int) (exifOff, gpsOff, extraOff int) {
	top := nIFD0 + boolToInt(nExif > 0) + boolToInt(nGPS > 0)
	exifOff = 8 + ifdLen(top)
	gpsOff = exifOff + ifdLen(nExif)
	extraOff = gpsOff + ifdLen(nGPS)
	return exifOff, gpsOff, extraOff
}

// buildTIFF assembles a little-endian TIFF block with IFD0 and, optionally, an
// Exif sub-IFD and a GPS IFD, followed by a block of out-of-line values.
func buildTIFF(ifd0, exif, gps []ifdEntry, extra []byte) []byte {
	var out bytes.Buffer
	out.Write([]byte{'I', 'I'})
	binary.Write(&out, binary.LittleEndian, uint16(42))
	binary.Write(&out, binary.LittleEndian, uint32(8)) // IFD0 begins right after

	exifOff, gpsOff, _ := layout(len(ifd0), len(exif), len(gps))

	full := append([]ifdEntry{}, ifd0...)
	if len(exif) > 0 {
		full = append(full, ifdEntry{tag: tagExifIFD, typ: typeLong, count: 1, value: inline32(uint32(exifOff))})
	}
	if len(gps) > 0 {
		full = append(full, ifdEntry{tag: tagGPSIFD, typ: typeLong, count: 1, value: inline32(uint32(gpsOff))})
	}

	writeIFD(&out, full)
	if len(exif) > 0 {
		writeIFD(&out, exif)
	} else {
		writeIFD(&out, nil)
	}
	if len(gps) > 0 {
		writeIFD(&out, gps)
	} else {
		writeIFD(&out, nil)
	}
	out.Write(extra)
	return out.Bytes()
}

func writeIFD(out *bytes.Buffer, entries []ifdEntry) {
	binary.Write(out, binary.LittleEndian, uint16(len(entries)))
	for _, e := range entries {
		binary.Write(out, binary.LittleEndian, e.tag)
		binary.Write(out, binary.LittleEndian, e.typ)
		binary.Write(out, binary.LittleEndian, e.count)
		out.Write(e.value)
	}
	binary.Write(out, binary.LittleEndian, uint32(0)) // no next IFD
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// rationalsAt encodes degrees/minutes/seconds as three unsigned rationals.
func rationalsAt(deg, min, sec uint32) []byte {
	var b bytes.Buffer
	for _, pair := range [][2]uint32{{deg, 1}, {min, 1}, {sec, 100}} {
		binary.Write(&b, binary.LittleEndian, pair[0])
		binary.Write(&b, binary.LittleEndian, pair[1])
	}
	return b.Bytes()
}

func TestReadsTheCaptureTimeTheShutterRecorded(t *testing.T) {
	// The string is 20 bytes including its terminator, so it lives at an offset
	// rather than inline - the path most real timestamps take.
	stamp := append([]byte("2026:08:20 08:30:00"), 0)
	_, _, extra := layout(0, 1, 0)
	tiff := buildTIFF(nil,
		[]ifdEntry{{tag: tagDateTimeOriginal, typ: typeASCII, count: uint32(len(stamp)), value: inline32(uint32(extra))}},
		nil, stamp)

	meta, err := FromJPEG(buildJPEG(tiff))
	if err != nil {
		t.Fatalf("FromJPEG: %v", err)
	}
	if meta.CapturedAt == nil {
		t.Fatal("no capture time read")
	}
	want := time.Date(2026, 8, 20, 8, 30, 0, 0, time.UTC)
	if !meta.CapturedAt.Equal(want) {
		t.Fatalf("capture time: got %s, want %s", meta.CapturedAt, want)
	}
}

func TestReadsAMaldivianPositionWithItsHemispheres(t *testing.T) {
	lat := rationalsAt(4, 3, 730)    // 4 deg 3' 7.30"  N
	lon := rationalsAt(72, 56, 5300) // 72 deg 56' 53.00" E
	blob := append(append([]byte{}, lat...), lon...)

	_, _, base := layout(0, 0, 4)
	tiff := buildTIFF(nil, nil, []ifdEntry{
		{tag: tagGPSLatitudeRef, typ: typeASCII, count: 2, value: []byte{'N', 0, 0, 0}},
		{tag: tagGPSLatitude, typ: typeRational, count: 3, value: inline32(uint32(base))},
		{tag: tagGPSLongitudeRef, typ: typeASCII, count: 2, value: []byte{'E', 0, 0, 0}},
		{tag: tagGPSLongitude, typ: typeRational, count: 3, value: inline32(uint32(base + len(lat)))},
	}, blob)

	meta, err := FromJPEG(buildJPEG(tiff))
	if err != nil {
		t.Fatalf("FromJPEG: %v", err)
	}
	if meta.Lat == nil || meta.Lon == nil {
		t.Fatal("no position read")
	}
	if got := *meta.Lat; got < 4.051 || got > 4.053 {
		t.Fatalf("latitude: got %f, want about 4.0520", got)
	}
	if got := *meta.Lon; got < 72.947 || got > 72.949 {
		t.Fatalf("longitude: got %f, want about 72.9481", got)
	}
}

func TestASouthernAndWesternFixIsNegated(t *testing.T) {
	lat := rationalsAt(4, 3, 730)
	lon := rationalsAt(72, 56, 5300)
	blob := append(append([]byte{}, lat...), lon...)

	_, _, base := layout(0, 0, 4)
	tiff := buildTIFF(nil, nil, []ifdEntry{
		{tag: tagGPSLatitudeRef, typ: typeASCII, count: 2, value: []byte{'S', 0, 0, 0}},
		{tag: tagGPSLatitude, typ: typeRational, count: 3, value: inline32(uint32(base))},
		{tag: tagGPSLongitudeRef, typ: typeASCII, count: 2, value: []byte{'W', 0, 0, 0}},
		{tag: tagGPSLongitude, typ: typeRational, count: 3, value: inline32(uint32(base + len(lat)))},
	}, blob)

	meta, _ := FromJPEG(buildJPEG(tiff))
	if meta.Lat == nil || *meta.Lat > 0 {
		t.Fatalf("southern latitude was not negated: %v", meta.Lat)
	}
	if meta.Lon == nil || *meta.Lon > 0 {
		t.Fatalf("western longitude was not negated: %v", meta.Lon)
	}
}

func TestAPositionWithNoHemisphereIsDiscarded(t *testing.T) {
	lat := rationalsAt(4, 3, 730)
	lon := rationalsAt(72, 56, 5300)
	blob := append(append([]byte{}, lat...), lon...)

	// No GPSLatitudeRef. Without it the sign is a guess, and a guessed sign puts
	// a Maldivian reef in the northern Indian Ocean or off Sumatra.
	_, _, base := layout(0, 0, 3)
	tiff := buildTIFF(nil, nil, []ifdEntry{
		{tag: tagGPSLatitude, typ: typeRational, count: 3, value: inline32(uint32(base))},
		{tag: tagGPSLongitudeRef, typ: typeASCII, count: 2, value: []byte{'E', 0, 0, 0}},
		{tag: tagGPSLongitude, typ: typeRational, count: 3, value: inline32(uint32(base + len(lat)))},
	}, blob)

	meta, _ := FromJPEG(buildJPEG(tiff))
	if meta.Lat != nil || meta.Lon != nil {
		t.Fatalf("a fix with no hemisphere was kept: %v, %v", meta.Lat, meta.Lon)
	}
}

func TestHalfAFixIsNoFix(t *testing.T) {
	lat := rationalsAt(4, 3, 730)
	_, _, base := layout(0, 0, 2)
	tiff := buildTIFF(nil, nil, []ifdEntry{
		{tag: tagGPSLatitudeRef, typ: typeASCII, count: 2, value: []byte{'N', 0, 0, 0}},
		{tag: tagGPSLatitude, typ: typeRational, count: 3, value: inline32(uint32(base))},
	}, lat)

	meta, _ := FromJPEG(buildJPEG(tiff))
	// A latitude with no longitude would otherwise be stored as a position on
	// the prime meridian.
	if meta.Lat != nil || meta.Lon != nil {
		t.Fatalf("half a fix was kept: %v, %v", meta.Lat, meta.Lon)
	}
}

func TestAnImplausibleClockIsRejected(t *testing.T) {
	for _, stamp := range []string{
		"1980:01:01 00:00:00", // a camera whose battery died
		"2199:01:01 00:00:00", // a clock set past the end of the project
		"not a timestamp",
	} {
		raw := append([]byte(stamp), 0)
		_, _, extra := layout(0, 1, 0)
		tiff := buildTIFF(nil,
			[]ifdEntry{{tag: tagDateTimeOriginal, typ: typeASCII, count: uint32(len(raw)), value: inline32(uint32(extra))}},
			nil, raw)

		meta, _ := FromJPEG(buildJPEG(tiff))
		if meta.CapturedAt != nil {
			t.Fatalf("%q was accepted as a capture time: %s", stamp, meta.CapturedAt)
		}
	}
}

// The parser reads bytes a stranger uploaded, so the interesting inputs are the
// dishonest ones. None of these may panic or read outside the segment.
func TestMalformedExifIsSurvivedRatherThanTrusted(t *testing.T) {
	cases := map[string][]byte{
		"not a jpeg at all":   []byte("just some text"),
		"jpeg with no exif":   {0xFF, 0xD8, 0xFF, 0xD9},
		"truncated after SOI": {0xFF, 0xD8},
		"empty":               {},
		"app1 that lies about its length": {
			0xFF, 0xD8, 0xFF, 0xE1, 0xFF, 0xFF, 'E', 'x', 'i', 'f', 0, 0,
		},
		"exif with a bad byte order": buildJPEG([]byte{'X', 'Y', 42, 0, 8, 0, 0, 0}),
		"exif with a bad magic":      buildJPEG([]byte{'I', 'I', 99, 0, 8, 0, 0, 0}),
		"ifd offset past the end": buildJPEG([]byte{
			'I', 'I', 42, 0, 0xFF, 0xFF, 0xFF, 0x7F,
		}),
		"ifd claiming more entries than it has": buildJPEG([]byte{
			'I', 'I', 42, 0, 8, 0, 0, 0, 0xFF, 0xFF,
		}),
	}

	for name, data := range cases {
		t.Run(name, func(t *testing.T) {
			// The contract is total: any bytes in, usable Metadata out, no panic.
			meta, _ := FromJPEG(data)
			if meta.CapturedAt != nil || meta.Lat != nil || meta.Lon != nil {
				t.Fatalf("malformed input produced metadata: %+v", meta)
			}
		})
	}
}

func TestAValueOffsetPointingOutsideTheSegmentIsIgnored(t *testing.T) {
	// A string entry whose offset points far past the block. A parser that
	// trusted it would read whatever followed the segment in memory.
	tiff := buildTIFF(nil,
		[]ifdEntry{{tag: tagDateTimeOriginal, typ: typeASCII, count: 20, value: inline32(0x7FFFFFF0)}},
		nil, nil)

	meta, _ := FromJPEG(buildJPEG(tiff))
	if meta.CapturedAt != nil {
		t.Fatalf("an out-of-range offset was followed: %s", meta.CapturedAt)
	}
}

func TestARationalWithAZeroDenominatorIsIgnored(t *testing.T) {
	var blob bytes.Buffer
	for i := 0; i < 3; i++ {
		binary.Write(&blob, binary.LittleEndian, uint32(4))
		binary.Write(&blob, binary.LittleEndian, uint32(0)) // division by zero
	}
	_, _, base := layout(0, 0, 4)
	tiff := buildTIFF(nil, nil, []ifdEntry{
		{tag: tagGPSLatitudeRef, typ: typeASCII, count: 2, value: []byte{'N', 0, 0, 0}},
		{tag: tagGPSLatitude, typ: typeRational, count: 3, value: inline32(uint32(base))},
		{tag: tagGPSLongitudeRef, typ: typeASCII, count: 2, value: []byte{'E', 0, 0, 0}},
		{tag: tagGPSLongitude, typ: typeRational, count: 3, value: inline32(uint32(base))},
	}, blob.Bytes())

	meta, _ := FromJPEG(buildJPEG(tiff))
	if meta.Lat != nil {
		t.Fatalf("a zero denominator produced a coordinate: %v", meta.Lat)
	}
}
