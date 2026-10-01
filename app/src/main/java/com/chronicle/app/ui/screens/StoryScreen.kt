package com.chronicle.app.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chronicle.app.R
import com.chronicle.app.domain.Story
import com.chronicle.app.domain.StoryChapter
import com.chronicle.app.domain.StoryComposer
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.FrostedIconButton
import com.chronicle.app.ui.design.GlassSurface
import com.chronicle.app.ui.design.Hairline
import com.chronicle.app.ui.design.LocalBottomBarPadding
import com.chronicle.app.ui.design.Radius
import com.chronicle.app.ui.design.Spacing
import com.chronicle.app.ui.design.SurfaceLevel
import com.chronicle.app.ui.theme.Fraunces
import com.chronicle.app.ui.vm.StoryViewModel
import java.io.File
import java.time.LocalDate

/** Above this size the story is shared file-only; EXTRA_TEXT is skipped. */
private const val SHARE_TEXT_LIMIT = 50_000

/**
 * Shares the whole story as a Markdown file via FileProvider — big stories
 * exceed EXTRA_TEXT binder limits. Small stories also keep an EXTRA_TEXT
 * copy for receivers that ignore streams.
 */
private fun shareStory(context: Context, story: Story?) {
    val markdown = story?.toMarkdown().orEmpty()
    val uri = writeShareFile(context, markdown)?.let { file ->
        runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrNull()
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_TITLE, "The Story So Far")
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
            // Some receivers read the grant from ClipData rather than the flag.
            clipData = ClipData.newRawUri("story", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (markdown.length <= SHARE_TEXT_LIMIT) {
                putExtra(Intent.EXTRA_TEXT, markdown)
            }
        } else {
            // File write failed — degrade to text, capped, rather than nothing.
            putExtra(Intent.EXTRA_TEXT, markdown.take(SHARE_TEXT_LIMIT))
        }
    }
    context.startActivity(Intent.createChooser(intent, "Share the story"))
}

/** Writes the story to `cacheDir/share/story-<today>.md`, clearing old shares. */
private fun writeShareFile(context: Context, markdown: String): File? =
    runCatching {
        val dir = File(context.cacheDir, "share")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "story-${LocalDate.now()}.md").apply { writeText(markdown) }
    }.getOrNull()

@Composable
fun StoryScreen(viewModel: StoryViewModel, onOpenChapter: (LocalDate) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val story = state.story
    val context = LocalContext.current
    val p = Chron.palette
    val type = Chron.type

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            start = Spacing.screenMargin, end = Spacing.screenMargin,
            top = Spacing.x4, bottom = LocalBottomBarPadding.current,
        ),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Story",
                        style = type.title,
                        color = p.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Your weeks, written back to you.",
                        style = type.serifItalic,
                        color = p.textSecondary,
                    )
                }
                FrostedIconButton(
                    icon = R.drawable.ic_share,
                    contentDescription = "Share story",
                    onClick = { shareStory(context, story) },
                )
            }
            Spacer(Modifier.height(Spacing.x6))
        }

        // Each chapter carries its week-start Monday — the stable key the
        // reader route is built on, immune to renumbering after a delete.
        val located = story?.chapters.orEmpty().mapIndexedNotNull { i, chapter ->
            state.chapterWeeks.getOrNull(i)?.let { chapter to it }
        }
        if (located.isEmpty()) {
            item {
                Spacer(Modifier.height(96.dp))
                Text(
                    text = "Your first chapter is still being lived.",
                    style = type.headline,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Chapters are composed each week from the entries you keep.",
                    style = type.body,
                    color = p.textSecondary,
                )
            }
        } else {
            // The newest chapter is the hero; the rest float as an index.
            val current = located.last()
            val previous = located.dropLast(1).asReversed()

            item(key = "hero-${current.second}") {
                HeroChapterCard(
                    chapter = current.first,
                    onClick = { onOpenChapter(current.second) },
                    modifier = Modifier.animateItem(),
                )
            }

            if (previous.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(Spacing.x10))
                }
                items(previous, key = { "prev-${it.second}" }) { (chapter, weekStart) ->
                    Column(Modifier.animateItem()) {
                        ChapterIndexRow(
                            chapter = chapter,
                            onClick = { onOpenChapter(weekStart) },
                        )
                        Hairline(modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}

/** The current chapter — an elevated frosted card with a reading excerpt. */
@Composable
private fun HeroChapterCard(
    chapter: StoryChapter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Chron.palette
    val type = Chron.type
    GlassSurface(
        level = SurfaceLevel.Elevated,
        shape = RoundedCornerShape(Radius.lg),
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.x6)) {
            Text(
                text = chapter.title,
                style = type.meta,
                color = p.accent,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = chapter.subtitle,
                style = type.headline,
                color = p.textPrimary,
            )
            if (chapter.paragraphs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = chapter.paragraphs.first(),
                    style = type.serifItalic,
                    color = p.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "${chapter.entryCount} entries · ${chapter.wordCount} words",
                style = type.numeric,
                color = p.textTertiary,
            )
        }
    }
}

/** A previous chapter — a numeral and the week, floating on the background. */
@Composable
private fun ChapterIndexRow(chapter: StoryChapter, onClick: () -> Unit) {
    val p = Chron.palette
    val type = Chron.type
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${chapter.title}, ${chapter.subtitle}"
            }
            .clickable(onClick = onClick, role = Role.Button)
            .padding(vertical = Spacing.x4),
    ) {
        Text(
            text = StoryComposer.roman(chapter.number),
            style = type.title,
            color = p.textTertiary,
            modifier = Modifier.width(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = chapter.subtitle,
                style = type.section,
                color = p.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${chapter.entryCount} entries · ${chapter.wordCount} words",
                style = type.numeric,
                color = p.textTertiary,
            )
        }
    }
}

/** Full narrative for one chapter — the long read. */
@Composable
fun StoryChapterScreen(
    viewModel: StoryViewModel,
    weekStart: LocalDate,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chapter = state.chapterAt(weekStart)
    // Chapters regroup from live entries — if this week's chapter vanished
    // mid-read (entries deleted), step back out once rather than strand the
    // reader on an empty page. Waits for the first real emission so a cold
    // start doesn't pop before the story loads.
    var popped by remember { mutableStateOf(false) }
    LaunchedEffect(state.story, chapter) {
        if (!popped && state.story != null && chapter == null) {
            popped = true
            onBack()
        }
    }
    val context = LocalContext.current
    val p = Chron.palette
    val type = Chron.type

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenMargin),
    ) {
        Spacer(Modifier.height(Spacing.x4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FrostedIconButton(
                icon = R.drawable.ic_back,
                contentDescription = "Back",
                onClick = onBack,
            )
            Spacer(Modifier.weight(1f))
            if (chapter != null) {
                FrostedIconButton(
                    icon = R.drawable.ic_share,
                    contentDescription = "Share story",
                    onClick = { shareStory(context, state.story) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.x8))

        if (chapter == null) {
            Text(
                text = "This chapter has slipped away.",
                style = type.body,
                color = p.textSecondary,
            )
        } else {
            Text(
                text = chapter.title,
                style = type.meta,
                color = p.accent,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = chapter.subtitle,
                style = type.display,
                color = p.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.x10))

            chapter.paragraphs.forEachIndexed { i, paragraph ->
                Text(
                    text = paragraph,
                    style = type.body.copy(
                        fontFamily = Fraunces,
                        fontSize = if (i == 0) 20.sp else 18.sp,
                        lineHeight = if (i == 0) 32.sp else 29.sp,
                    ),
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(22.dp))
            }

            Spacer(Modifier.height(Spacing.x4))
            Text(
                text = "${chapter.entryCount} entries · ${chapter.wordCount} words",
                style = type.numeric,
                color = p.textTertiary,
            )
            Spacer(Modifier.height(64.dp))
        }
    }
}
