/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.wear.compose.material3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

internal fun <T> provideScopeContent(
    contentColor: State<Color>,
    textStyle: TextStyle,
    content: (@Composable T.() -> Unit)
): (@Composable T.() -> Unit) = {
    val color = contentColor.value
    CompositionLocalProvider(
        LocalContentColor provides color,
        LocalContentAlpha provides color.alpha,
        LocalTextStyle provides textStyle,
    ) {
        content()
    }
}

internal fun <T> provideScopeContent(
    color: State<Color>,
    content: (@Composable T.() -> Unit)
): (@Composable T.() -> Unit) = {
    CompositionLocalProvider(
        LocalContentColor provides color.value,
        LocalContentAlpha provides color.value.alpha,
    ) {
        content()
    }
}

internal fun <T> provideNullableScopeContent(
    contentColor: State<Color>,
    textStyle: TextStyle,
    content: (@Composable T.() -> Unit)?
): (@Composable T.() -> Unit)? = content?.let {
    {
        val color = contentColor.value
        CompositionLocalProvider(
            LocalContentColor provides color,
            LocalContentAlpha provides color.alpha,
            LocalTextStyle provides textStyle
        ) {
            content()
        }
    }
}

internal fun <T> provideNullableScopeContent(
    contentColor: State<Color>,
    content: (@Composable T.() -> Unit)?
): (@Composable T.() -> Unit)? = content?.let {
    {
        val color = contentColor.value
        CompositionLocalProvider(
            LocalContentColor provides color,
            LocalContentAlpha provides color.alpha
        ) {
            content()
        }
    }
}
