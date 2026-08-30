package httpapi_test

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"hash/crc32"
	"image"
	"image/color"
	"image/gif"
	"testing"
	"time"
)

// bombPNG hand-assembles a PNG whose IHDR declares dimensions far larger than
// its payload.
//
// It is written by hand because it cannot be encoded: producing a 30000x30000
// PNG through image/png would mean allocating the 3.6 GB image this test exists
// to prevent the server from allocating. Only the header matters - Go's PNG
// decoder sizes its pixel buffer from IHDR before it reads a single scanline, so
// a few hundred bytes on the wire buy an allocation of gigabytes.
//
// That is the whole attack: every byte-count check in the upload handler sees a
// small file, because it is one.
func bombPNG(t *testing.T, width, height uint32) []byte {
	t.Helper()

	var out bytes.Buffer
	out.Write([]byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})

	var ihdr bytes.Buffer
	binary.Write(&ihdr, binary.BigEndian, width)
	binary.Write(&ihdr, binary.BigEndian, height)
	ihdr.Write([]byte{
		8, // bit depth
		6, // colour type: RGBA, so each pixel costs four bytes
		0, // deflate
		0, // adaptive filtering
		0, // no interlace
	})
	writeChunk(&out, "IHDR", ihdr.Bytes())

	// A short, well-formed deflate stream. The decoder allocates from IHDR long
	// before it discovers there are not enough scanlines here, which is exactly
	// the ordering that makes the header the only thing worth trusting.
	var idat bytes.Buffer
	zw := zlib.NewWriter(&idat)
	zw.Write(make([]byte, 1024))
	zw.Close()
	writeChunk(&out, "IDAT", idat.Bytes())

	writeChunk(&out, "IEND", nil)
	return out.Bytes()
}

func writeChunk(out *bytes.Buffer, kind string, payload []byte) {
	binary.Write(out, binary.BigEndian, uint32(len(payload)))
	body := append([]byte(kind), payload...)
	out.Write(body)
	binary.Write(out, binary.BigEndian, crc32.ChecksumIEEE(body))
}

// gifBytes is a real, decodable GIF: a valid image in a format the pipeline is
// not specified for, which is a different refusal from "this is not an image".
func gifBytes(t *testing.T) []byte {
	t.Helper()
	img := image.NewPaletted(image.Rect(0, 0, 16, 16), color.Palette{color.Black, color.White})
	var out bytes.Buffer
	if err := gif.Encode(&out, img, nil); err != nil {
		t.Fatalf("encode gif: %v", err)
	}
	return out.Bytes()
}

// jpegWithExif produces a real, decodable JPEG carrying a hand-built EXIF block
// with a capture time and a GPS fix.
//
// The APP1 segment is spliced in directly after SOI because Go's image/jpeg
// encoder writes no metadata at all - which is convenient here, since it means
// the only EXIF in the file is the EXIF this function put there, and a test
// asserting the segment is gone afterwards cannot be fooled by an encoder that
// never wrote one.
func jpegWithExif(t *testing.T, captured time.Time, lat, lon float64) []byte {
	t.Helper()

	base := jpegOf(t, 64, 64)
	if len(base) < 2 || base[0] != 0xFF || base[1] != 0xD8 {
		t.Fatal("encoded jpeg does not start with SOI")
	}

	// Layout, fixed by the entry counts below: IFD0 holds two pointers, the Exif
	// IFD one timestamp, the GPS IFD four entries, then the out-of-line values.
	ifdLen := func(n int) int { return 2 + n*12 + 4 }
	exifOff := 8 + ifdLen(2)
	gpsOff := exifOff + ifdLen(1)
	valuesOff := gpsOff + ifdLen(4)

	stamp := append([]byte(captured.UTC().Format("2006:01:02 15:04:05")), 0)
	latVal := dmsRationals(lat)
	lonVal := dmsRationals(lon)

	stampOff := valuesOff
	latOff := stampOff + len(stamp)
	lonOff := latOff + len(latVal)

	var tiff bytes.Buffer
	tiff.Write([]byte{'I', 'I'})
	binary.Write(&tiff, binary.LittleEndian, uint16(42))
	binary.Write(&tiff, binary.LittleEndian, uint32(8))

	putIFD(&tiff, [][4]any{
		{uint16(0x8769), uint16(4), uint32(1), uint32(exifOff)}, // Exif sub-IFD
		{uint16(0x8825), uint16(4), uint32(1), uint32(gpsOff)},  // GPS IFD
	})
	putIFD(&tiff, [][4]any{
		{uint16(0x9003), uint16(2), uint32(len(stamp)), uint32(stampOff)}, // DateTimeOriginal
	})
	putIFD(&tiff, [][4]any{
		{uint16(0x0001), uint16(2), uint32(2), hemisphere(lat, 'N', 'S')},
		{uint16(0x0002), uint16(5), uint32(3), uint32(latOff)},
		{uint16(0x0003), uint16(2), uint32(2), hemisphere(lon, 'E', 'W')},
		{uint16(0x0004), uint16(5), uint32(3), uint32(lonOff)},
	})
	tiff.Write(stamp)
	tiff.Write(latVal)
	tiff.Write(lonVal)

	payload := append([]byte("Exif\x00\x00"), tiff.Bytes()...)

	var out bytes.Buffer
	out.Write(base[:2]) // SOI
	out.Write([]byte{0xFF, 0xE1})
	binary.Write(&out, binary.BigEndian, uint16(len(payload)+2))
	out.Write(payload)
	out.Write(base[2:])
	return out.Bytes()
}

// putIFD writes one IFD from {tag, type, count, value} tuples.
func putIFD(out *bytes.Buffer, entries [][4]any) {
	binary.Write(out, binary.LittleEndian, uint16(len(entries)))
	for _, e := range entries {
		binary.Write(out, binary.LittleEndian, e[0].(uint16))
		binary.Write(out, binary.LittleEndian, e[1].(uint16))
		binary.Write(out, binary.LittleEndian, e[2].(uint32))
		binary.Write(out, binary.LittleEndian, e[3].(uint32))
	}
	binary.Write(out, binary.LittleEndian, uint32(0))
}

// hemisphere packs a one-character reference plus its terminator into the four
// inline bytes an EXIF value field provides.
func hemisphere(v float64, positive, negative byte) uint32 {
	c := positive
	if v < 0 {
		c = negative
	}
	return uint32(c)
}

// dmsRationals splits a signed decimal degree into the three unsigned rationals
// EXIF stores. Seconds keep two decimal places, which is about 30 cm - far finer
// than any consumer GPS fix, so nothing meaningful is lost in the rounding.
func dmsRationals(v float64) []byte {
	if v < 0 {
		v = -v
	}
	deg := uint32(v)
	minutesFloat := (v - float64(deg)) * 60
	min := uint32(minutesFloat)
	sec := uint32((minutesFloat - float64(min)) * 60 * 100)

	var b bytes.Buffer
	for _, pair := range [][2]uint32{{deg, 1}, {min, 1}, {sec, 100}} {
		binary.Write(&b, binary.LittleEndian, pair[0])
		binary.Write(&b, binary.LittleEndian, pair[1])
	}
	return b.Bytes()
}
