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
