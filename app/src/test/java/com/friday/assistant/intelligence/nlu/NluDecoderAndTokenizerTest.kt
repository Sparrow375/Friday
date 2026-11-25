package com.friday.assistant.intelligence.nlu

import org.junit.Assert.assertEquals
import org.junit.Test

class NluDecoderAndTokenizerTest {

    @Test
    fun `wordpiece tokenizer splits unknown subwords cleanly`() {
        val vocab = mapOf(
            "[UNK]" to 100,
            "play" to 10,
            "sun" to 11,
            "##flower" to 12,
            "wifi" to 13
        )
        val tok = WordpieceTokenizer(vocab)
        val (ids, strings) = tok.tokenizeWithTokens("play sunflower wifi")
        assertEquals(listOf(10, 11, 12, 13), ids)
        assertEquals("sunflower", tok.convertTokensToString(listOf("sun", "##flower")))
        assertEquals(listOf("play", "sun", "##flower", "wifi"), strings)
    }

    @Test
    fun `decodeBioSlots promotes orphan I-tag into valid entity span`() {
        val slotLabels = listOf(
            "O",
            "B-CONTACT", "I-CONTACT",
            "B-MESSAGE", "I-MESSAGE"
        )
        // [CLS] text [mom(I-CONTACT)] saying [hello(B-MESSAGE)] [there(I-MESSAGE)] [SEP]
        val tokens = listOf("[CLS]", "text", "mom", "saying", "hello", "there", "[SEP]")
        val slotIds = listOf(0, 0, 2, 0, 3, 4, 0) // index 2 is orphan I-CONTACT!

        val decoded = NluIntentClassifier.decodeBioSlots(
            tokenStrings = tokens,
            predictedSlotIds = slotIds,
            slotLabels = slotLabels,
            detokenizer = { toks -> toks.joinToString(" ") }
        )

        assertEquals("mom", decoded["CONTACT"])
        assertEquals("hello there", decoded["MESSAGE"])
    }
}
