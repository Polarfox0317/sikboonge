package io.github.polarfox0317.sikboonge.ui.components

import android.icu.text.BreakIterator
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Display-only wrapping: the value-based editor keeps IME, clipboard and selection in raw text. */
internal object GraphemeWrapVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        val breaks = BooleanArray(original.length + 1)
        val iterator = BreakIterator.getCharacterInstance().apply { setText(original) }
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            if (boundary in 1 until original.length &&
                original[boundary - 1] != '\n' && original[boundary] != '\n'
            ) breaks[boundary] = true
            boundary = iterator.next()
        }
        val output = StringBuilder(original.length * 2)
        val originalOffsets = IntArray(original.length + 1)
        val transformedOffsets = ArrayList<Int>(original.length * 2 + 1).apply { add(0) }
        original.indices.forEach { offset ->
            if (breaks[offset]) {
                output.append('\u200B')
                transformedOffsets.add(offset)
            }
            originalOffsets[offset] = output.length
            output.append(original[offset])
            transformedOffsets.add(offset + 1)
        }
        originalOffsets[original.length] = output.length
        return TransformedText(
            AnnotatedString(output.toString()),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = originalOffsets[offset]
                override fun transformedToOriginal(offset: Int): Int = transformedOffsets[offset]
            },
        )
    }
}
