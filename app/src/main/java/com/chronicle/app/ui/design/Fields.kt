package com.chronicle.app.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Multiline-capable input on frosted glass — placeholder in serif italic. */
@Composable
fun FrostedTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    secret: Boolean = false,
    minHeight: Dp = 52.dp,
    focusRequester: FocusRequester? = null,
) {
    val p = Chron.palette
    GlassSurface(
        level = SurfaceLevel.Frosted,
        shape = RoundedCornerShape(Radius.sm),
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = Chron.type.body.copy(color = p.textPrimary),
            cursorBrush = SolidColor(p.accent),
            singleLine = singleLine,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = Chron.type.serifItalic,
                            color = p.textTertiary,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
