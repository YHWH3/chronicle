package com.chronicle.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronicle.app.R
import com.chronicle.app.domain.StoryChapter
import com.chronicle.app.ui.components.ChronicleIcon
import com.chronicle.app.ui.components.Hairline
import com.chronicle.app.ui.components.Masthead
import com.chronicle.app.ui.components.MetaText
import com.chronicle.app.ui.theme.ChronicleExtras
import com.chronicle.app.ui.theme.Fraunces
import com.chronicle.app.ui.theme.SerifItalic
import com.chronicle.app.ui.vm.StoryViewModel

@Composable
fun StoryScreen(viewModel: StoryViewModel, onOpenChapter: (Int) -> Unit) {
    val state by viewModel.state.collectAsState()
    val story = state.story

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 28.dp, end = 28.dp, top = 20.dp, bottom = com.chronicle.app.ui.design.LocalBottomBarPadding.current,
        ),
    ) {
        item {
            Masthead(kicker = "Being written", title = "The Story")
            Spacer(Modifier.height(10.dp))
            if (story != null && story.totalEntries > 0) {
                MetaText(
                    text = "${story.totalDays} days · ${story.totalEntries} entries · " +
                        "${story.totalWords} words · about a ${story.readingMinutes}-minute read",
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (story == null || story.chapters.isEmpty()) {
            item {
                Spacer(Modifier.height(80.dp))
                Text(
                    "The story hasn't begun.",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                MetaText("Each day you keep becomes part of it")
            }
        } else {
            items(story.chapters.asReversed(), key = { it.number }) { chapter ->
                Column(Modifier.animateItem()) {
                    ChapterCard(chapter = chapter, onClick = { onOpenChapter(chapter.number) })
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(chapter: StoryChapter, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ChronicleExtras.hairline, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Row {
            MetaText(
                text = chapter.title + if (chapter.ongoing) " · unfinished" else "",
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            ChronicleIcon(
                R.drawable.ic_chevron_right, null,
                tint = ChronicleExtras.faint,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(chapter.subtitle, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (chapter.paragraphs.isNotEmpty()) {
            Text(
                text = chapter.paragraphs.first(),
                style = SerifItalic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
            Spacer(Modifier.height(14.dp))
        }
        Hairline()
        Spacer(Modifier.height(10.dp))
        MetaText("${chapter.entryCount} entries · ${chapter.wordCount} words")
    }
}

/** Full narrative for one chapter — the long read. */
@Composable
fun StoryChapterScreen(
    viewModel: StoryViewModel,
    chapterNumber: Int,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val chapter = state.story?.chapters?.firstOrNull { it.number == chapterNumber }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChronicleIcon(
                R.drawable.ic_back, "Back",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onBack),
            )
            Spacer(Modifier.weight(1f))
            if (chapter != null) {
                ChronicleIcon(
                    R.drawable.ic_share, "Share",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable {
                        val text = state.story?.toMarkdown().orEmpty()
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            putExtra(Intent.EXTRA_TITLE, "The Story So Far")
                        }
                        context.startActivity(Intent.createChooser(intent, "Share the story"))
                    },
                )
            }
        }
        Spacer(Modifier.height(28.dp))

        if (chapter == null) {
            MetaText("This chapter has slipped away.")
        } else {
            MetaText(text = chapter.title, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(chapter.subtitle, style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(6.dp))
            MetaText("${chapter.entryCount} entries · ${chapter.wordCount} words")
            Spacer(Modifier.height(28.dp))

            for (paragraph in chapter.paragraphs) {
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = Fraunces,
                        fontSize = 18.sp,
                        lineHeight = 30.sp,
                    ),
                )
                Spacer(Modifier.height(22.dp))
            }

            Spacer(Modifier.height(24.dp))
            MetaText("· · ·", color = ChronicleExtras.faint)
            Spacer(Modifier.height(48.dp))
        }
    }
}
