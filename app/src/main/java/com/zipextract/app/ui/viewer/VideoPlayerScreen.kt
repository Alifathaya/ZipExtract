package com.zipextract.app.ui.viewer

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.zipextract.app.R
import com.zipextract.app.data.FileActions
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    file: File,
    sourceUri: Uri? = null,
    playlist: List<File> = listOf(file),
    initialIndex: Int = 0,
    onClose: () -> Unit,
    onPageChanged: (File) -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val files = remember(playlist, file) {
        playlist.ifEmpty { listOf(file) }
            .distinctBy { it.absolutePath }
            .ifEmpty { listOf(file) }
    }
    val startIndex = remember(files, file, initialIndex) {
        val byPath = files.indexOfFirst { it.absolutePath == file.absolutePath }
        when {
            byPath >= 0 -> byPath
            initialIndex in files.indices -> initialIndex
            else -> 0
        }
    }
    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { files.size },
    )
    val currentFile = files.getOrElse(pagerState.currentPage) { file }
    val onPageChangedState = rememberUpdatedState(onPageChanged)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                files.getOrNull(page)?.let { onPageChangedState.value(it) }
            }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentFile.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                        )
                        Text(
                            text = if (files.size > 1) {
                                stringResource(
                                    R.string.video_player_hint_swipe,
                                    pagerState.currentPage + 1,
                                    files.size,
                                )
                            } else {
                                stringResource(R.string.video_player_hint)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!FileActions.shareFile(context, currentFile)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.video_share_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.video_share),
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (!FileActions.openWith(context, currentFile)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.video_open_external_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = stringResource(R.string.video_open_external),
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding),
        ) {
            HorizontalPager(
                state = pagerState,
                key = { page -> files[page].absolutePath },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageFile = files[page]
                // Only the active page creates an ExoPlayer (avoids many decoders).
                if (page == pagerState.currentPage ||
                    page == pagerState.currentPage - 1 ||
                    page == pagerState.currentPage + 1
                ) {
                    VideoPage(
                        file = pageFile,
                        sourceUri = if (pageFile.absolutePath == file.absolutePath) sourceUri else null,
                        playWhenReady = page == pagerState.currentPage,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
    }
}

@Composable
private fun VideoPage(
    file: File,
    sourceUri: Uri?,
    playWhenReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playbackUri = remember(file.absolutePath, sourceUri) {
        sourceUri ?: FileActions.uriFor(context, file)
    }

    val player = remember(playbackUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(playbackUri))
            prepare()
        }
    }

    LaunchedEffect(playWhenReady, player) {
        player.playWhenReady = playWhenReady
        if (!playWhenReady) player.pause()
    }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.video_play_failed,
                        error.message ?: "unknown",
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        player.addListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_DESTROY -> player.release()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            player.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = true
                controllerShowTimeoutMs = 3_500
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                this.player = player
            }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
            }
        },
        modifier = modifier.padding(bottom = 4.dp),
    )
}
