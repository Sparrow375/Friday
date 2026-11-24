package com.friday.assistant.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StreamImaAdpcmDecoderTest {

    @Test
    fun chunkedStatefulDecode_matchesSingleBufferDecodeExactly() {
        val raw = ByteArray(256) { idx -> ((idx * 37 + 13) and 0xFF).toByte() }

        val fullDecoded = ImaAdpcmDecoder.decode(raw)

        val streamDecoder = StreamImaAdpcmDecoder()
        val chunk1 = streamDecoder.decodeChunk(raw, 0, 128)
        val chunk2 = streamDecoder.decodeChunk(raw, 128, 128)
        val combined = chunk1 + chunk2

        assertArrayEquals(fullDecoded, combined, 0.00001f)
    }
}
