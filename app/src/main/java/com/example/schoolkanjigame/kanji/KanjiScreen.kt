package com.example.schoolkanjigame.kanji

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun KanjiScreen(
    viewModel: KanjiViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, uiState.mode, uiState.isFinished) {
        viewModel.reconcileYoutubeRewardUsage()
        viewModel.setReadingScreenActive(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.reconcileYoutubeRewardUsage()
                    viewModel.setReadingScreenActive(true)
                }
                Lifecycle.Event.ON_STOP -> viewModel.setReadingScreenActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setReadingScreenActive(false)
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { paddingValues ->
        val selectedHistoryEntry = uiState.selectedHistoryEntry
        when {
            selectedHistoryEntry != null -> HistoryDetailScreen(
                entry = selectedHistoryEntry,
                onBack = viewModel::hideHistoryDetail,
                modifier = Modifier.padding(paddingValues),
            )

            uiState.isHistoryVisible -> HistoryScreen(
                historyEntries = uiState.historyEntries,
                onBack = viewModel::hideHistory,
                onOpenDetail = viewModel::showHistoryDetail,
                onDeleteHistory = viewModel::deleteHistoryEntry,
                modifier = Modifier.padding(paddingValues),
            )

            uiState.isParentAdminVisible -> ParentAdminScreen(
                uiState = uiState,
                onGradeEnabledChanged = viewModel::setGradeEnabled,
                onYoutubeInAppEnabledChanged = viewModel::setYoutubeInAppEnabled,
                onYoutubeMinutesPer100CorrectChanged = viewModel::setYoutubeMinutesPer100Correct,
                onYoutubeRewardAvailableSecondsChanged = viewModel::setYoutubeRewardAvailableSeconds,
                onSaveYoutubeWifiSettings = viewModel::saveYoutubeWifiSettings,
                onDeleteYoutubeWifiSettings = viewModel::deleteYoutubeWifiSettings,
                onUnlock = viewModel::unlockParentAdmin,
                onSavePassword = viewModel::saveParentPassword,
                onBack = viewModel::hideParentAdmin,
                modifier = Modifier.padding(paddingValues),
            )

            uiState.isYoutubeRewardVisible -> YoutubeRewardScreen(
                uiState = uiState,
                onBack = viewModel::hideYoutubeReward,
                onChargeableChanged = viewModel::setYoutubeRewardChargeable,
                onYoutubePlaybackProgress = viewModel::saveYoutubePlaybackProgress,
                modifier = Modifier.padding(paddingValues),
            )

            !uiState.isSessionStarted -> StartSettingsScreen(
                uiState = uiState,
                onGradeSelected = viewModel::setSelectedGrade,
                onQuestionCountChanged = viewModel::setQuestionCount,
                onTimerChanged = viewModel::setReadingSecondsPerQuestion,
                onStart = { viewModel.startReadingMode() },
                onHistory = viewModel::showHistory,
                onParentAdmin = viewModel::showParentAdmin,
                onYoutubeReward = viewModel::startYoutubeRewardSession,
                modifier = Modifier.padding(paddingValues),
            )

            uiState.isFinished -> ResultScreen(
                uiState = uiState,
                onRetryWrongQuestions = viewModel::retryWrongReadingQuestions,
                onBackToSettings = viewModel::returnToSettings,
                modifier = Modifier.padding(paddingValues),
            )

            uiState.mode == LearningMode.Reading -> ReadingQuizScreen(
                uiState = uiState,
                onAnswerSelected = viewModel::submitReadingAnswer,
                modifier = Modifier.padding(paddingValues),
            )

            uiState.mode == LearningMode.Writing -> WritingQuizScreen(
                uiState = uiState,
                onStrokesChanged = viewModel::updateWritingStrokes,
                onClear = viewModel::clearWritingCanvas,
                onJudge = viewModel::judgeCurrentWritingCharacter,
                onSkip = viewModel::skipCurrentWritingQuestion,
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

@Composable
private fun StartSettingsScreen(
    uiState: KanjiUiState,
    onGradeSelected: (Int) -> Unit,
    onQuestionCountChanged: (Int) -> Unit,
    onTimerChanged: (Int) -> Unit,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onParentAdmin: () -> Unit,
    onYoutubeReward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFB9E9FF),
                        Color(0xFF3F9DFF),
                        Color(0xFF1F73E8),
                    ),
                ),
            )
            .verticalScroll(scrollState)
            .padding(18.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xAAE9F7FF),
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    text = "漢字アプリ",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF16408F),
                )

                GradeSelector(
                    selectedGrade = uiState.selectedGrade,
                    enabledGrades = uiState.enabledGrades,
                    columns = 6,
                    onGradeSelected = onGradeSelected,
                )

                SettingsSlider(
                    title = "タイマー",
                    valueText = uiState.readingSecondsPerQuestion.formatTimerText(),
                    value = uiState.readingSecondsPerQuestion,
                    valueRange = 10..180,
                    steps = 16,
                    stepSize = 10,
                    onValueChanged = onTimerChanged,
                )

                SettingsSlider(
                    title = "問題数",
                    valueText = "${uiState.questionCount}問",
                    value = uiState.questionCount,
                    valueRange = 5..100,
                    steps = 18,
                    stepSize = 5,
                    onValueChanged = onQuestionCountChanged,
                )

                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    shape = RoundedCornerShape(31.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF86DC23),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(text = "スタート", fontSize = 24.sp, fontWeight = FontWeight.Black)
                }

                OutlinedButton(
                    onClick = onHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(text = "履歴", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onYoutubeReward,
                    enabled = uiState.isYoutubeInAppEnabled && uiState.youtubeRewardAvailableSeconds > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF0033),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = if (uiState.isYoutubeInAppEnabled) {
                            "YouTube ${uiState.youtubeRewardAvailableSeconds.formatRewardTimeText()}"
                        } else {
                            "YouTube 無効"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                    )
                }

                OutlinedButton(
                    onClick = onParentAdmin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(text = "管理画面", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ParentAdminScreen(
    uiState: KanjiUiState,
    onGradeEnabledChanged: (Int, Boolean) -> Unit,
    onYoutubeInAppEnabledChanged: (Boolean) -> Unit,
    onYoutubeMinutesPer100CorrectChanged: (Int) -> Unit,
    onYoutubeRewardAvailableSecondsChanged: (Int) -> Unit,
    onSaveYoutubeWifiSettings: (String, String) -> Unit,
    onDeleteYoutubeWifiSettings: (String) -> Unit,
    onUnlock: (String) -> Unit,
    onSavePassword: (String, String) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var localPasswordError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var hasWifiPermission by remember {
        mutableStateOf(context.hasFineLocationPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasWifiPermission = granted
    }
    val connectedWifiSsid = remember(hasWifiPermission) {
        if (hasWifiPermission) context.currentWifiSsid().orEmpty() else ""
    }
    var youtubeWifiSsid by remember(uiState.youtubeWifiSsid, connectedWifiSsid) {
        mutableStateOf(uiState.youtubeWifiSsid.ifBlank { connectedWifiSsid })
    }
    var youtubeWifiPassword by remember(uiState.youtubeWifiPassword) {
        mutableStateOf(uiState.youtubeWifiPassword)
    }
    var wifiSsidOptions by remember { mutableStateOf(emptyList<String>()) }
    var showWifiSsidDialog by remember { mutableStateOf(false) }
    var wifiSettingsMessage by remember { mutableStateOf<String?>(null) }
    var wifiPasswordConfirmed by remember { mutableStateOf(false) }
    var wifiTestTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(hasWifiPermission) {
        if (!hasWifiPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    wifiTestTarget?.let { target ->
        DisposableEffect(target) {
            wifiSettingsMessage = "接続確認中: ${target.first}"
            val cancel = requestYoutubeWifiConnection(
                context = context,
                ssid = target.first,
                password = target.second,
                onStatus = { message ->
                    wifiSettingsMessage = message
                    wifiPasswordConfirmed = false
                    wifiTestTarget = null
                },
                onConnected = { message ->
                    wifiSettingsMessage = message
                    wifiPasswordConfirmed = true
                    wifiTestTarget = null
                },
            )
            onDispose { cancel() }
        }
    }

    if (showWifiSsidDialog) {
        AlertDialog(
            onDismissRequest = { showWifiSsidDialog = false },
            title = { Text(text = "SSIDを選択") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (wifiSsidOptions.isEmpty()) {
                        item {
                            Text(text = "SSIDが見つかりませんでした。")
                        }
                    } else {
                        items(
                            count = wifiSsidOptions.size,
                            key = { index -> wifiSsidOptions[index] },
                        ) { index ->
                            OutlinedButton(
                                onClick = {
                                    youtubeWifiSsid = wifiSsidOptions[index]
                                    wifiPasswordConfirmed = false
                                    showWifiSsidDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = wifiSsidOptions[index],
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWifiSsidDialog = false }) {
                    Text(text = "閉じる")
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "admin-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(text = "戻る", fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "管理画面",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
            }
        }

        if (!uiState.isParentAuthenticated) {
            item(key = "admin-password-unlock") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "パスワードを入力してください",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("パスワード") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        )
                        uiState.parentAuthError?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Button(
                            onClick = { onUnlock(password) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(text = "開く", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            return@LazyColumn
        }

        item(key = "admin-settings") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    EnabledGradeSelector(
                        enabledGrades = uiState.enabledGrades,
                        gradeProgress = uiState.gradeProgress,
                        onGradeEnabledChanged = onGradeEnabledChanged,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "アプリ内YouTube",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                text = if (uiState.isYoutubeInAppEnabled) "有効" else "無効",
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Switch(
                            checked = uiState.isYoutubeInAppEnabled,
                            onCheckedChange = onYoutubeInAppEnabledChanged,
                        )
                    }

                    SettingsSlider(
                        title = "100問正解につき可能な視聴時間",
                        valueText = "${uiState.youtubeMinutesPer100Correct}分",
                        value = uiState.youtubeMinutesPer100Correct,
                        valueRange = 0..120,
                        steps = 23,
                        stepSize = 5,
                        onValueChanged = onYoutubeMinutesPer100CorrectChanged,
                    )

                    Text(
                        text = "総得点 ${uiState.youtubeRewardTotalScore}点",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Black,
                    )

                    SettingsSlider(
                        title = "YouTube残り時間",
                        valueText = uiState.youtubeRewardAvailableSeconds.formatRewardTimeText(),
                        value = uiState.youtubeRewardAvailableSeconds.coerceIn(0, MAX_YOUTUBE_REWARD_SECONDS),
                        valueRange = 0..MAX_YOUTUBE_REWARD_SECONDS,
                        steps = 119,
                        stepSize = 60,
                        onValueChanged = onYoutubeRewardAvailableSecondsChanged,
                    )

                    Text(
                        text = "YouTube WiFi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (youtubeWifiSsid.isBlank()) "SSID未選択" else youtubeWifiSsid,
                            modifier = Modifier.weight(0.9f),
                            color = if (youtubeWifiSsid.isBlank()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(
                            onClick = {
                                if (!hasWifiPermission) {
                                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    wifiSettingsMessage = "SSID取得には位置情報権限が必要です。"
                                } else {
                                    wifiSsidOptions = context.availableWifiSsids()
                                    showWifiSsidDialog = true
                                    wifiSettingsMessage = if (wifiSsidOptions.isEmpty()) {
                                        "SSIDを取得できませんでした。端末のWiFi設定と位置情報を確認してください。"
                                    } else {
                                        "${wifiSsidOptions.size}件のSSIDを取得しました。"
                                    }
                                }
                            },
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(text = "SSID取得", fontWeight = FontWeight.Bold)
                        }
                        OutlinedTextField(
                            value = youtubeWifiPassword,
                            onValueChange = {
                                youtubeWifiPassword = it
                                wifiPasswordConfirmed = false
                            },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(if (wifiPasswordConfirmed) "WiFiパスワード ✅" else "WiFiパスワード")
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        OutlinedButton(
                            onClick = {
                                if (youtubeWifiSsid.isBlank()) {
                                    wifiSettingsMessage = "SSIDを選択してください。"
                                } else if (!hasWifiPermission) {
                                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    wifiSettingsMessage = "接続確認には位置情報権限が必要です。"
                                } else {
                                    wifiPasswordConfirmed = false
                                    wifiTestTarget = youtubeWifiSsid to youtubeWifiPassword
                                }
                            },
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(text = "確認", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                if (youtubeWifiSsid.isBlank()) {
                                    wifiSettingsMessage = "SSIDを選択してください。"
                                } else {
                                    onSaveYoutubeWifiSettings(youtubeWifiSsid, youtubeWifiPassword)
                                    wifiSettingsMessage = "YouTube WiFi設定を保存しました。"
                                }
                            },
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(text = "保存", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (uiState.youtubeWifiNetworks.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "保存済みWiFi",
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold,
                            )
                            uiState.youtubeWifiNetworks.forEach { network ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            youtubeWifiSsid = network.ssid
                                            youtubeWifiPassword = network.password
                                            wifiPasswordConfirmed = false
                                            wifiSettingsMessage = "WiFi設定を選択しました: ${network.ssid}"
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text(
                                            text = network.ssid,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onDeleteYoutubeWifiSettings(network.ssid)
                                            if (youtubeWifiSsid == network.ssid) {
                                                youtubeWifiSsid = ""
                                                youtubeWifiPassword = ""
                                            }
                                            wifiPasswordConfirmed = false
                                            wifiSettingsMessage = "保存済みWiFiを削除しました: ${network.ssid}"
                                        },
                                        modifier = Modifier.height(48.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text(text = "削除", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    wifiSettingsMessage?.let { message ->
                        Text(
                            text = message,
                            color = if (wifiPasswordConfirmed) Color(0xFF047857) else MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        item(key = "admin-password-change") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (uiState.isParentPasswordConfigured) "パスワード変更" else "パスワード設定",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = currentPassword,
                            onValueChange = { currentPassword = it.filter(Char::isDigit) },
                            modifier = Modifier.weight(1f),
                            label = { Text("現在") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        )
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it.filter(Char::isDigit) },
                            modifier = Modifier.weight(1f),
                            label = { Text("新規") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        )
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it.filter(Char::isDigit) },
                            modifier = Modifier.weight(1f),
                            label = { Text("確認") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        )
                        Button(
                            onClick = {
                                if (newPassword != confirmPassword) {
                                    localPasswordError = "確認用のパスワードが一致しません。"
                                    return@Button
                                }
                                localPasswordError = null
                                if (onSavePassword(currentPassword, newPassword)) {
                                    currentPassword = ""
                                    newPassword = ""
                                    confirmPassword = ""
                                }
                            },
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(text = "保存", fontWeight = FontWeight.Bold)
                        }
                    }
                    localPasswordError?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    uiState.parentPasswordMessage?.let { message ->
                        Text(
                            text = message,
                            color = if (message.contains("保存")) Color(0xFF047857) else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YoutubeRewardScreen(
    uiState: KanjiUiState,
    onBack: () -> Unit,
    onChargeableChanged: (Boolean) -> Unit,
    onYoutubePlaybackProgress: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasWifiPermission by remember(uiState.youtubeWifiSsid) {
        mutableStateOf(context.hasFineLocationPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasWifiPermission = granted
    }

    LaunchedEffect(uiState.youtubeWifiSsid, hasWifiPermission) {
        if (
            uiState.youtubeWifiSsid.isNotBlank() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasWifiPermission
        ) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val wifiStatus = rememberYoutubeWifiStatus(uiState, hasWifiPermission)
    var isYoutubePageLoaded by remember { mutableStateOf(false) }
    var showWifiConnectionAlert by remember { mutableStateOf(false) }
    val youtubeStartUrl = remember(
        uiState.youtubeLastPlaybackUrl,
        uiState.youtubeLastPlaybackSeconds,
    ) {
        uiState.youtubeLastPlaybackUrl.toYoutubeResumeUrl(uiState.youtubeLastPlaybackSeconds)
    }

    LaunchedEffect(wifiStatus.isYoutubeNetworkReady) {
        isYoutubePageLoaded = false
        showWifiConnectionAlert = false
        if (!wifiStatus.isYoutubeNetworkReady) {
            onChargeableChanged(false)
        }
    }
    LaunchedEffect(uiState.youtubeWifiSsid, wifiStatus.isYoutubeNetworkReady) {
        if (uiState.youtubeWifiSsid.isNotBlank() && !wifiStatus.isYoutubeNetworkReady) {
            delay(YOUTUBE_WIFI_CONNECTION_TIMEOUT_MILLIS)
            showWifiConnectionAlert = true
        }
    }
    LaunchedEffect(wifiStatus.isYoutubeNetworkReady, isYoutubePageLoaded) {
        onChargeableChanged(wifiStatus.isYoutubeNetworkReady && isYoutubePageLoaded)
    }
    DisposableEffect(Unit) {
        onDispose { onChargeableChanged(false) }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                onBack()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    if (showWifiConnectionAlert) {
        AlertDialog(
            onDismissRequest = { showWifiConnectionAlert = false },
            text = {
                Text(text = "このアプリ内にある管理画面でWiFiの接続をしてください")
            },
            confirmButton = {
                TextButton(onClick = { showWifiConnectionAlert = false }) {
                    Text(text = "OK")
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(text = "戻る", fontWeight = FontWeight.Bold)
            }
            Text(
                text = "残り ${uiState.youtubeRewardAvailableSeconds.formatRewardTimeText()}",
                color = Color(0xFFFF0033),
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }

        if (wifiStatus.isYoutubeNetworkReady) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        val mainHandler = Handler(Looper.getMainLooper())
                        addJavascriptInterface(
                            YoutubePlaybackProgressBridge(
                                mainHandler = mainHandler,
                                onProgress = onYoutubePlaybackProgress,
                            ),
                            YOUTUBE_PROGRESS_BRIDGE_NAME,
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                isYoutubePageLoaded = true
                                view?.injectYoutubeProgressReporter()
                            }
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        loadUrl(youtubeStartUrl)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "YouTubeを開く準備をしています",
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun rememberYoutubeWifiStatus(
    uiState: KanjiUiState,
    hasWifiPermission: Boolean,
): YoutubeWifiStatus {
    val context = LocalContext.current
    val wifiCandidates = remember(
        uiState.youtubeWifiSsid,
        uiState.youtubeWifiPassword,
        uiState.youtubeWifiNetworks,
    ) {
        (listOf(
            YoutubeWifiNetwork(
                ssid = uiState.youtubeWifiSsid,
                password = uiState.youtubeWifiPassword,
            ),
        ) + uiState.youtubeWifiNetworks)
            .filter { it.ssid.isNotBlank() }
            .distinctBy { it.ssid }
    }
    var status by remember(wifiCandidates) {
        mutableStateOf(
            if (wifiCandidates.isEmpty()) {
                YoutubeWifiStatus("YouTube WiFiが未設定です。現在のネットワークで開きます。", isYoutubeNetworkReady = true)
            } else {
                YoutubeWifiStatus("WiFi接続リクエスト中: ${wifiCandidates.first().ssid}", isYoutubeNetworkReady = false)
            },
        )
    }

    DisposableEffect(wifiCandidates, hasWifiPermission) {
        if (wifiCandidates.isEmpty()) {
            status = YoutubeWifiStatus("YouTube WiFiが未設定です。現在のネットワークで開きます。", isYoutubeNetworkReady = true)
            onDispose { }
        } else if (!hasWifiPermission) {
            status = YoutubeWifiStatus("WiFi接続には位置情報権限が必要です。", isYoutubeNetworkReady = false)
            onDispose { }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            status = YoutubeWifiStatus("この端末ではアプリからのWiFi接続リクエストに対応していません。", isYoutubeNetworkReady = false)
            onDispose { }
        } else {
            val connectivityManager = context.getSystemService(
                Context.CONNECTIVITY_SERVICE,
            ) as ConnectivityManager
            val mainHandler = Handler(Looper.getMainLooper())
            val registeredCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

            fun unregister(callback: ConnectivityManager.NetworkCallback) {
                if (registeredCallbacks.remove(callback)) {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }

            fun tryWifi(index: Int) {
                val candidate = wifiCandidates.getOrNull(index)
                if (candidate == null) {
                    Log.d(SCHOOL_MATH_WIFI_TAG, "All YouTube reward WiFi candidates unavailable")
                    connectivityManager.bindProcessToNetwork(null)
                    status = YoutubeWifiStatus("保存済みWiFiに接続できませんでした。視聴時間は減りません。", isYoutubeNetworkReady = false)
                    return
                }

                val ssid = candidate.ssid
                val attemptText = if (wifiCandidates.size == 1) {
                    ssid
                } else {
                    "$ssid (${index + 1}/${wifiCandidates.size})"
                }

                if (context.currentWifiSsid() == ssid) {
                    Log.d(SCHOOL_MATH_WIFI_TAG, "Already connected to YouTube reward WiFi: $ssid")
                    status = YoutubeWifiStatus("WiFi接続中: $ssid", isYoutubeNetworkReady = true)
                    return
                }

                status = YoutubeWifiStatus("WiFi接続リクエスト中: $attemptText", isYoutubeNetworkReady = false)
                lateinit var callback: ConnectivityManager.NetworkCallback
                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(SCHOOL_MATH_WIFI_TAG, "YouTube reward WiFi available: $ssid")
                        connectivityManager.bindProcessToNetwork(network)
                        mainHandler.post {
                            status = YoutubeWifiStatus("WiFi接続中: $ssid", isYoutubeNetworkReady = true)
                        }
                    }

                    override fun onUnavailable() {
                        Log.d(SCHOOL_MATH_WIFI_TAG, "YouTube reward WiFi unavailable: $ssid")
                        mainHandler.post {
                            unregister(callback)
                            status = YoutubeWifiStatus("接続できませんでした: $ssid。次のWiFiを試します。", isYoutubeNetworkReady = false)
                            tryWifi(index + 1)
                        }
                    }

                    override fun onLost(network: Network) {
                        Log.d(SCHOOL_MATH_WIFI_TAG, "YouTube reward WiFi lost: $ssid")
                        mainHandler.post {
                            unregister(callback)
                            connectivityManager.bindProcessToNetwork(null)
                            status = YoutubeWifiStatus("接続が切れました: $ssid。次のWiFiを試します。", isYoutubeNetworkReady = false)
                            tryWifi(index + 1)
                        }
                    }
                }

                runCatching {
                    Log.d(SCHOOL_MATH_WIFI_TAG, "Request YouTube reward WiFi: $ssid")
                    val specifierBuilder = WifiNetworkSpecifier.Builder()
                        .setSsid(ssid)
                    if (candidate.password.isNotBlank()) {
                        specifierBuilder.setWpa2Passphrase(candidate.password)
                    }
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .setNetworkSpecifier(specifierBuilder.build())
                        .build()
                    connectivityManager.requestNetwork(request, callback)
                    registeredCallbacks.add(callback)
                }.onFailure { throwable ->
                    Log.d(SCHOOL_MATH_WIFI_TAG, "Failed to request YouTube reward WiFi: $ssid", throwable)
                    status = YoutubeWifiStatus(
                        "接続リクエストに失敗しました: $ssid。次のWiFiを試します。",
                        isYoutubeNetworkReady = false,
                    )
                    tryWifi(index + 1)
                }
            }

            tryWifi(0)

            onDispose {
                Log.d(SCHOOL_MATH_WIFI_TAG, "Release YouTube reward WiFi requests")
                connectivityManager.bindProcessToNetwork(null)
                registeredCallbacks.toList().forEach(::unregister)
            }
        }
    }

    return status
}

private data class YoutubeWifiStatus(
    val message: String,
    val isYoutubeNetworkReady: Boolean,
)

private class YoutubePlaybackProgressBridge(
    private val mainHandler: Handler,
    private val onProgress: (String, Int) -> Unit,
) {
    @JavascriptInterface
    fun save(url: String?, seconds: Double) {
        val safeUrl = url.orEmpty()
        val safeSeconds = seconds.toInt().coerceAtLeast(0)
        if (safeUrl.isBlank()) return
        mainHandler.post {
            onProgress(safeUrl, safeSeconds)
        }
    }
}

private fun WebView.injectYoutubeProgressReporter() {
    evaluateJavascript(
        """
        (function() {
          if (window.__kanjiYoutubeProgressInstalled) return;
          window.__kanjiYoutubeProgressInstalled = true;
          function reportProgress() {
            try {
              var video = document.querySelector('video');
              if (!video || !isFinite(video.currentTime)) return;
              $YOUTUBE_PROGRESS_BRIDGE_NAME.save(window.location.href, Math.floor(video.currentTime));
            } catch (e) {}
          }
          setInterval(reportProgress, 2000);
          window.addEventListener('pagehide', reportProgress);
          document.addEventListener('visibilitychange', reportProgress);
          reportProgress();
        })();
        """.trimIndent(),
        null,
    )
}

private fun String.toYoutubeResumeUrl(seconds: Int): String {
    val safeUrl = takeIf { it.isHttpYoutubeUrl() } ?: YOUTUBE_HOME_URL
    val safeSeconds = seconds.coerceAtLeast(0)
    if (safeSeconds <= 0) return safeUrl

    return runCatching {
        val uri = Uri.parse(safeUrl)
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames
            .filterNot { it == "t" || it == "start" }
            .forEach { name ->
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        builder.appendQueryParameter("t", "${safeSeconds}s").build().toString()
    }.getOrElse {
        val separator = if (safeUrl.contains("?")) "&" else "?"
        "$safeUrl${separator}t=${safeSeconds}s"
    }
}

private fun String.isHttpYoutubeUrl(): Boolean =
    runCatching {
        val uri = Uri.parse(this)
        val scheme = uri.scheme.orEmpty()
        val host = uri.host.orEmpty()
        (scheme == "http" || scheme == "https") &&
            (host.endsWith("youtube.com") || host.endsWith("youtu.be"))
    }.getOrDefault(false)

private const val YOUTUBE_WIFI_CONNECTION_TIMEOUT_MILLIS = 10_000L
private const val YOUTUBE_HOME_URL = "https://m.youtube.com/"
private const val MAX_YOUTUBE_REWARD_SECONDS = 2 * 60 * 60
private const val YOUTUBE_PROGRESS_BRIDGE_NAME = "KanjiYoutubeProgress"

private fun Context.hasFineLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
private fun Context.currentWifiSsid(): String? {
    if (!hasFineLocationPermission()) return null
    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val ssid = wifiManager?.connectionInfo?.ssid
        ?.trim()
        ?.trim('"')
        .orEmpty()
    return ssid.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
}

@Suppress("DEPRECATION", "MissingPermission")
private fun Context.availableWifiSsids(): List<String> {
    if (!hasFineLocationPermission()) return emptyList()
    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return emptyList()

    runCatching {
        Log.d(SCHOOL_MATH_WIFI_TAG, "Start WiFi scan for admin SSID picker")
        wifiManager.startScan()
    }.onFailure { throwable ->
        Log.d(SCHOOL_MATH_WIFI_TAG, "Failed to start WiFi scan", throwable)
    }

    return wifiManager.scanResults
        .map { it.SSID.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
}

private fun requestYoutubeWifiConnection(
    context: Context,
    ssid: String,
    password: String,
    onStatus: (String) -> Unit,
    onConnected: (String) -> Unit,
): () -> Unit {
    if (ssid.isBlank()) {
        onStatus("SSIDを選択してください。")
        return {}
    }
    if (!context.hasFineLocationPermission()) {
        onStatus("WiFi接続には位置情報権限が必要です。")
        return {}
    }
    if (context.currentWifiSsid() == ssid) {
        Log.d(SCHOOL_MATH_WIFI_TAG, "Already connected to requested WiFi: $ssid")
        onConnected("接続OK: $ssid")
        return {}
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        onStatus("この端末ではアプリからのWiFi接続リクエストに対応していません。")
        return {}
    }

    val connectivityManager = context.getSystemService(
        Context.CONNECTIVITY_SERVICE,
    ) as ConnectivityManager
    val mainHandler = Handler(Looper.getMainLooper())
    var registered = false
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(SCHOOL_MATH_WIFI_TAG, "Admin WiFi test available: $ssid")
            mainHandler.post {
                connectivityManager.bindProcessToNetwork(network)
                onConnected("接続OK: $ssid")
            }
        }

        override fun onUnavailable() {
            Log.d(SCHOOL_MATH_WIFI_TAG, "Admin WiFi test unavailable: $ssid")
            mainHandler.post { onStatus("接続できませんでした: $ssid") }
        }

        override fun onLost(network: Network) {
            Log.d(SCHOOL_MATH_WIFI_TAG, "Admin WiFi test lost: $ssid")
            mainHandler.post { onStatus("接続が切れました: $ssid") }
        }
    }

    runCatching {
        Log.d(SCHOOL_MATH_WIFI_TAG, "Request admin WiFi test: $ssid")
        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotBlank()) {
            specifierBuilder.setWpa2Passphrase(password)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()
        connectivityManager.requestNetwork(request, callback)
        registered = true
    }.onFailure { throwable ->
        Log.d(SCHOOL_MATH_WIFI_TAG, "Failed to request admin WiFi test: $ssid", throwable)
        onStatus(throwable.message ?: "WiFi接続リクエストを開始できませんでした。")
    }

    return {
        Log.d(SCHOOL_MATH_WIFI_TAG, "Release admin WiFi test request: $ssid")
        connectivityManager.bindProcessToNetwork(null)
        if (registered) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
}

private const val SCHOOL_MATH_WIFI_TAG = "SchoolMathWifi"

@Composable
private fun HistoryScreen(
    historyEntries: List<KanjiHistoryEntry>,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<KanjiHistoryEntry?>(null) }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text(text = "履歴を削除しますか？")
            },
            text = {
                Text(text = entry.completedAtMillis.formatHistoryDateTime())
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteHistory(entry.id)
                        deleteTarget = null
                    },
                ) {
                    Text(text = "OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = "キャンセル")
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(text = "戻る", fontWeight = FontWeight.Bold)
            }
            Text(
                text = "履歴",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
        }

        if (historyEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "履歴はまだありません",
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    count = historyEntries.size,
                    key = { index -> historyEntries[index].id },
                ) { index ->
                    val entry = historyEntries[index]
                    HistoryRow(
                        entry = entry,
                        onOpenDetail = onOpenDetail,
                        onDelete = { deleteTarget = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: KanjiHistoryEntry,
    onOpenDetail: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val scoreText = entry.historyScoreSummaryText()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.completedAtMillis.formatHistoryDateTime(),
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                )
                Text(
                    text = "${entry.grade}年 / ${entry.questionCount}問 / ${entry.readingSecondsPerQuestion.formatTimerText()}",
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
                Text(
                    text = scoreText,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
            }
            Button(
                onClick = { onOpenDetail(entry.id) },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(text = "詳細", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onDelete,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(text = "削除", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HistoryDetailScreen(
    entry: KanjiHistoryEntry,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val readingCorrectCount = entry.readingReviews.count { it.isCorrect }
    val readingTotalCount = entry.readingReviews.size
    val writingCorrectCount = entry.writingReviews.count { it.isCorrectWritingReview() }
    val writingTotalCount = entry.writingReviews.size
    val scoreText = entry.historyScoreSummaryText()

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(text = "戻る", fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "履歴詳細",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "${entry.grade}年 / ${entry.questionCount}問 / ${entry.readingSecondsPerQuestion.formatTimerText()}",
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Text(
            text = entry.completedAtMillis.formatHistoryDateTime(),
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = scoreText,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
            lineHeight = 24.sp,
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (entry.readingReviews.isNotEmpty()) {
                    item(key = "history-reading-header") {
                        ReviewSectionHeader(
                            text = "読み",
                            scoreText = "$readingCorrectCount/$readingTotalCount",
                        )
                    }
                    items(
                        count = entry.readingReviews.size,
                        key = { index -> "history-reading-$index-${entry.readingReviews[index].questionId}" },
                    ) { index ->
                        ReadingReviewRow(
                            review = entry.readingReviews[index],
                            showCorrectAnswer = true,
                        )
                    }
                }
                if (entry.writingReviews.isNotEmpty()) {
                    item(key = "history-writing-header") {
                        ReviewSectionHeader(
                            text = "書き",
                            scoreText = "$writingCorrectCount/$writingTotalCount",
                        )
                    }
                    items(
                        count = entry.writingReviews.size,
                        key = { index -> "history-writing-$index-${entry.writingReviews[index].questionId}" },
                    ) { index ->
                        WritingReviewRow(review = entry.writingReviews[index])
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeSelector(
    selectedGrade: Int,
    enabledGrades: Set<Int> = (1..6).toSet(),
    columns: Int = 3,
    onGradeSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "学年",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = Color(0xFF16408F),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..6).chunked(columns.coerceIn(1, 6)).forEach { rowGrades ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowGrades.forEach { grade ->
                        val selected = grade == selectedGrade
                        val enabled = grade in enabledGrades
                        Button(
                            onClick = { onGradeSelected(grade) },
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF86DC23) else Color(0xFF1F73E8),
                                contentColor = if (selected) Color(0xFF16408F) else Color.White,
                                disabledContainerColor = Color(0xFFE5E7EB),
                                disabledContentColor = Color(0xFF6B7280),
                            ),
                        ) {
                            Text(
                                text = "${grade}年",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnabledGradeSelector(
    enabledGrades: Set<Int>,
    gradeProgress: List<KanjiGradeProgress>,
    onGradeEnabledChanged: (Int, Boolean) -> Unit,
) {
    val progressByGrade = remember(gradeProgress) {
        gradeProgress.associateBy { it.grade }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "出題できる学年",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = Color(0xFF16408F),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..6).chunked(3).forEach { rowGrades ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowGrades.forEach { grade ->
                        val checked = grade in enabledGrades
                        val canChange = !checked || enabledGrades.size > 1
                        val progress = progressByGrade[grade]
                        Button(
                            onClick = {
                                if (canChange) {
                                    onGradeEnabledChanged(grade, !checked)
                                }
                            },
                            enabled = canChange,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (checked) Color(0xFF86DC23) else Color(0xFFE5E7EB),
                                contentColor = if (checked) Color(0xFF16408F) else Color(0xFF6B7280),
                                disabledContainerColor = Color(0xFF86DC23),
                                disabledContentColor = Color(0xFF16408F),
                            ),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "${grade}年",
                                    fontSize = 15.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.Black,
                                )
                                Text(
                                    text = "${progress?.solvedCount ?: 0}問 / ${progress?.achievementPercent ?: 0}%",
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    title: String,
    valueText: String,
    value: Int,
    valueRange: IntRange,
    steps: Int,
    stepSize: Int = 1,
    onValueChanged: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = Color(0xFF16408F),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color(0xFF16408F),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val steppedValue = (it / stepSize).roundToInt() * stepSize
                onValueChanged(steppedValue.coerceIn(valueRange))
            },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps,
        )
    }
}

private fun Int.formatTimerText(): String {
    val minutes = this / 60
    val seconds = this % 60
    return when {
        minutes == 0 -> "${seconds}秒"
        seconds == 0 -> "${minutes}分"
        else -> "${minutes}分${seconds}秒"
    }
}

private fun Int.formatRewardTimeText(): String {
    val safeSeconds = coerceAtLeast(0)
    val minutes = safeSeconds / 60
    val seconds = safeSeconds % 60
    return when {
        minutes == 0 -> "${seconds}秒"
        seconds == 0 -> "${minutes}分"
        else -> "${minutes}分${seconds}秒"
    }
}

private fun Long.formatHistoryDateTime(): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(this))

private fun KanjiHistoryEntry.historyScoreSummaryText(): String =
    "読み ${readingReviews.count { it.isCorrect }}/${readingReviews.size} : " +
        "書き ${writingReviews.count { it.isCorrectWritingReview() }}/${writingReviews.size}"

private fun KanjiWritingReview.isCorrectWritingReview(): Boolean =
    !isSkipped && writtenAnswer == correctAnswer

@Composable
private fun ReadingQuizScreen(
    uiState: KanjiUiState,
    onAnswerSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val question = uiState.currentQuestion ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "読み ${uiState.currentQuestionIndex + 1} / ${uiState.questions.size}",
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(
            progress = { uiState.readingTimeRemaining / uiState.readingSecondsPerQuestion.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "残り ${uiState.readingTimeRemaining} 秒",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        RubySentence(
            sentence = question.fullSentence,
            sentenceReading = question.sentenceReading,
            markedSentence = question.markedSentence,
            markedSentenceReading = question.markedSentenceReading,
            targetText = question.targetText,
            targetReading = question.readingAnswers.firstOrNull().orEmpty(),
            showTargetRuby = false,
            modifier = Modifier.fillMaxWidth(),
        )
        TranslationSentences(
            englishSentence = question.englishSentence,
            spanishSentence = question.spanishSentence,
        )
        Spacer(modifier = Modifier.weight(1f))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(uiState.shuffledReadingAnswers) { answer ->
                Button(
                    onClick = { onAnswerSelected(answer) },
                    modifier = Modifier.height(96.dp),
                ) {
                    Text(answer, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun WritingQuizScreen(
    uiState: KanjiUiState,
    onStrokesChanged: (List<DrawnStroke>) -> Unit,
    onClear: () -> Unit,
    onJudge: (Float, Float) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val question = uiState.currentQuestion ?: return
    val writingTargetReading = question.readingAnswers.firstOrNull().orEmpty()
    val writingPrompt = question.fullSentence.replaceFirst(question.targetText, writingTargetReading)
    val writingMarkedPrompt = question.markedSentence.replaceFirst(
        "[${question.targetText}]",
        "[$writingTargetReading]",
    )
    val density = LocalDensity.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "書き ${uiState.currentQuestionIndex + 1} / ${uiState.questions.size}",
            style = MaterialTheme.typography.titleMedium,
        )
        RubySentence(
            sentence = writingPrompt,
            sentenceReading = question.sentenceReading,
            markedSentence = writingMarkedPrompt,
            markedSentenceReading = question.markedSentenceReading,
            targetText = writingTargetReading,
            targetReading = writingTargetReading,
            modifier = Modifier.fillMaxWidth(),
        )
        TranslationSentences(
            englishSentence = question.englishSentence,
            spanishSentence = question.spanishSentence,
        )
        Text(
            text = "${uiState.currentWritingCharIndex + 1}文字目 / ${question.writingAnswer.length}文字中",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val actionGap = 12.dp
            val canvasWidth = with(density) { (maxWidth - actionGap).toPx() * 4f / 5f }
            val canvasHeight = with(density) { 280.dp.toPx() }

            Row(
                horizontalArrangement = Arrangement.spacedBy(actionGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                KanjiCanvasView(
                    strokes = uiState.strokes,
                    onStrokesChanged = onStrokesChanged,
                    modifier = Modifier.weight(4f),
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(280.dp),
                ) {
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("クリア")
                    }
                    OutlinedButton(
                        onClick = onSkip,
                        enabled = uiState.recognitionState != RecognitionState.Loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("スキップ")
                    }
                    Button(
                        onClick = { onJudge(canvasWidth, canvasHeight) },
                        enabled = uiState.recognitionState != RecognitionState.Loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("確定")
                    }
                }
            }
        }

        when (val state = uiState.recognitionState) {
            RecognitionState.Idle -> InkModelStatus(uiState.inkModelState)
            RecognitionState.Loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("認識中...")
            }
            is RecognitionState.Success -> Text(
                text = if (state.isCorrect) {
                    "正解: ${state.recognizedText}"
                } else {
                    "もう一度: ${state.recognizedText}"
                },
                color = if (state.isCorrect) Color(0xFF047857) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
            is RecognitionState.Error -> Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun TranslationSentences(
    englishSentence: String,
    spanishSentence: String,
    modifier: Modifier = Modifier,
) {
    if (englishSentence.isBlank() && spanishSentence.isBlank()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (englishSentence.isNotBlank()) {
            Text(
                text = "EN: $englishSentence",
                fontSize = 24.sp,
                lineHeight = 32.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (spanishSentence.isNotBlank()) {
            Text(
                text = "ES: $spanishSentence",
                fontSize = 24.sp,
                lineHeight = 32.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun ResultScreen(
    uiState: KanjiUiState,
    onRetryWrongQuestions: () -> Unit,
    onBackToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRetryNeeded = uiState.mode == LearningMode.Reading &&
        uiState.resultPhase == ResultPhase.RetryNeeded

    if (isRetryNeeded) {
        RetryResultScreen(
            uiState = uiState,
            onRetryWrongQuestions = onRetryWrongQuestions,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KanjiReviewList(
            uiState = uiState,
            showCorrectAnswer = true,
            showReadingReviews = false,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Button(
            onClick = onBackToSettings,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(text = "もう一度", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RetryResultScreen(
    uiState: KanjiUiState,
    onRetryWrongQuestions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wrongReviews = uiState.readingReviews
        .filterNot { it.isCorrect }
    val retryableWrongCount = wrongReviews.count { !it.isUnrecoverable }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "間違いチェック",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )

        Text(
            text = "リトライ対象 ${retryableWrongCount}問",
            fontSize = 40.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )

        KanjiReviewList(
            uiState = uiState,
            showCorrectAnswer = false,
            showTargetRuby = false,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Button(
            onClick = onRetryWrongQuestions,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(text = "間違えた問題を解く", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun KanjiReviewList(
    uiState: KanjiUiState,
    showCorrectAnswer: Boolean,
    showReadingReviews: Boolean = true,
    showTargetRuby: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val isRetryResult = uiState.mode == LearningMode.Reading &&
                uiState.resultPhase == ResultPhase.RetryNeeded
            val readingReviews = if (isRetryResult) {
                uiState.readingReviews
            } else {
                uiState.readingAllReviews.ifEmpty { uiState.readingReviews }
            }
            if (showReadingReviews && readingReviews.isNotEmpty()) {
                item(key = "reading-header") {
                    ReviewSectionHeader(text = "読み")
                }
                val reviews = if (isRetryResult) {
                    readingReviews.filterNot { it.isCorrect }
                } else {
                    readingReviews
                }
                items(
                    count = reviews.size,
                    key = { index -> "reading-${reviews[index].questionId}" },
                ) { index ->
                    ReadingReviewRow(
                        review = reviews[index],
                        showCorrectAnswer = showCorrectAnswer,
                        showTargetRuby = showTargetRuby,
                    )
                }
            }

            if (!isRetryResult) {
                val writingReviews = uiState.writingReviews.ifEmpty {
                    uiState.questions.mapIndexed { index, question ->
                        KanjiWritingReview(
                            questionId = question.id,
                            questionNumber = index + 1,
                            sentence = question.fullSentence,
                            sentenceReading = question.sentenceReading,
                            markedSentence = question.markedSentence,
                            markedSentenceReading = question.markedSentenceReading,
                            targetText = question.targetText,
                            targetReading = question.readingAnswers.firstOrNull().orEmpty(),
                            englishSentence = question.englishSentence,
                            spanishSentence = question.spanishSentence,
                            writtenAnswer = "",
                            correctAnswer = question.writingAnswer,
                            writtenStrokeGroups = emptyList(),
                            isSkipped = false,
                        )
                    }
                }
                if (writingReviews.isNotEmpty()) {
                    item(key = "writing-header") {
                        ReviewSectionHeader(text = "書き")
                    }
                    items(
                        count = writingReviews.size,
                        key = { index -> "writing-${writingReviews[index].questionId}" },
                    ) { index ->
                        WritingReviewRow(review = writingReviews[index])
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewSectionHeader(
    text: String,
    scoreText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
        )
        if (scoreText != null) {
            Text(
                text = scoreText,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun ReadingReviewRow(
    review: KanjiAnswerReview,
    showCorrectAnswer: Boolean,
    showTargetRuby: Boolean = true,
) {
    val textColor = if (review.isCorrect) Color.Black else Color(0xFFD00000)
    val selectedText = review.selectedAnswer ?: "未回答"
    val answerText = if (showCorrectAnswer || review.isUnrecoverable) {
        "選択: $selectedText   正解: ${review.correctAnswer}"
    } else {
        "選択: $selectedText"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = buildString {
                    append("${review.questionNumber}問目")
                    if (review.attemptNumber > 1) append(" / ${review.attemptNumber}回目")
                    if (review.isUnrecoverable) append(" / 回収不能")
                },
                color = if (review.isCorrect) Color(0xFF666666) else Color(0xFFD00000),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            RubySentence(
                sentence = review.sentence,
                sentenceReading = review.sentenceReading,
                markedSentence = review.markedSentence,
                markedSentenceReading = review.markedSentenceReading,
                targetText = review.targetText,
                targetReading = review.targetReading,
                textColor = textColor,
                textSize = 27.sp,
                showTargetRuby = showTargetRuby,
                modifier = Modifier.weight(1f),
            )
        }
        ReviewTranslationSentences(
            englishSentence = review.englishSentence,
            spanishSentence = review.spanishSentence,
        )
        Text(
            text = answerText,
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun WritingReviewRow(
    review: KanjiWritingReview,
) {
    val isCorrect = review.isCorrectWritingReview()
    val writtenAnswerColor = if (isCorrect) Color.Black else Color(0xFFD00000)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${review.questionNumber}問目",
                color = Color(0xFF666666),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            RubySentence(
                sentence = review.sentence,
                sentenceReading = review.sentenceReading,
                markedSentence = review.markedSentence,
                markedSentenceReading = review.markedSentenceReading,
                targetText = review.targetText,
                targetReading = review.targetReading,
                textColor = Color.Black,
                textSize = 27.sp,
                modifier = Modifier.weight(1f),
            )
        }
        ReviewTranslationSentences(
            englishSentence = review.englishSentence,
            spanishSentence = review.spanishSentence,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnswerComparisonColumn(
                label = "正解",
                modifier = Modifier.weight(1f),
            ) {
                CorrectAnswerPreview(answer = review.correctAnswer)
            }
            AnswerComparisonColumn(
                label = "手書き",
                modifier = Modifier.weight(1f),
            ) {
                if (review.isSkipped) {
                    EmptyWrittenAnswerPreview(
                        text = "スキップ",
                        textColor = writtenAnswerColor,
                    )
                } else if (review.writtenStrokeGroups.isNotEmpty()) {
                    WrittenAnswerPreview(
                        strokeGroups = review.writtenStrokeGroups,
                        strokeColor = writtenAnswerColor,
                    )
                } else if (review.writtenAnswer.isNotBlank()) {
                    CorrectAnswerPreview(
                        answer = review.writtenAnswer,
                        textColor = writtenAnswerColor,
                    )
                } else {
                    EmptyWrittenAnswerPreview(
                        text = "未記録",
                        textColor = writtenAnswerColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerComparisonColumn(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFF4B5563),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        content()
    }
}

@Composable
private fun CorrectAnswerPreview(
    answer: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Black,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
            .background(Color(0xFFF9FAFB), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            answer.ifBlank { "未記録" }.forEach { char ->
                Text(
                    text = char.toString(),
                    color = textColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 38.sp,
                    lineHeight = 44.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmptyWrittenAnswerPreview(
    modifier: Modifier = Modifier,
    text: String = "未記録",
    textColor: Color = Color(0xFF6B7280),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
            .background(Color(0xFFF9FAFB), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}
@Composable
private fun ReviewTranslationSentences(
    englishSentence: String,
    spanishSentence: String,
) {
    if (englishSentence.isBlank() && spanishSentence.isBlank()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (englishSentence.isNotBlank()) {
            Text(
                text = "EN: $englishSentence",
                color = Color(0xFF4B5563),
                fontSize = 21.sp,
                lineHeight = 28.sp,
            )
        }
        if (spanishSentence.isNotBlank()) {
            Text(
                text = "ES: $spanishSentence",
                color = Color(0xFF4B5563),
                fontSize = 21.sp,
                lineHeight = 28.sp,
            )
        }
    }
}

@Composable
private fun WrittenAnswerPreview(
    strokeGroups: List<List<DrawnStroke>>,
    modifier: Modifier = Modifier,
    strokeColor: Color = Color(0xFF111827),
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
            .background(Color(0xFFF9FAFB), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        if (strokeGroups.isEmpty()) return@Canvas

        val cellWidth = size.width / strokeGroups.size.coerceAtLeast(1)
        strokeGroups.forEachIndexed { index, strokes ->
            val points = strokes.flatMap { it.points }
            if (points.isEmpty()) return@forEachIndexed

            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val contentWidth = max(1f, maxX - minX)
            val contentHeight = max(1f, maxY - minY)
            val cellLeft = index * cellWidth
            val padding = 8f
            val drawableWidth = max(1f, cellWidth - padding * 2f)
            val drawableHeight = max(1f, size.height - padding * 2f)
            val scale = min(drawableWidth / contentWidth, drawableHeight / contentHeight)
            val offsetX = cellLeft + (cellWidth - contentWidth * scale) / 2f - minX * scale
            val offsetY = (size.height - contentHeight * scale) / 2f - minY * scale

            fun transformedPath(stroke: DrawnStroke): Path? {
                val strokePoints = stroke.points
                if (strokePoints.isEmpty()) return null
                return Path().apply {
                    moveTo(
                        strokePoints.first().x * scale + offsetX,
                        strokePoints.first().y * scale + offsetY,
                    )
                    strokePoints.drop(1).forEach { point ->
                        lineTo(point.x * scale + offsetX, point.y * scale + offsetY)
                    }
                }
            }

            strokes.forEach { stroke ->
                val strokePoints = stroke.points
                if (strokePoints.size == 1) {
                    val point = strokePoints.first()
                    drawCircle(
                        color = strokeColor,
                        radius = 4f,
                        center = androidx.compose.ui.geometry.Offset(
                            point.x * scale + offsetX,
                            point.y * scale + offsetY,
                        ),
                    )
                } else {
                    transformedPath(stroke)?.let { path ->
                        drawPath(
                            path = path,
                            color = strokeColor,
                            style = Stroke(
                                width = 5f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InkModelStatus(modelState: InkModelState) {
    when (modelState) {
        InkModelState.Idle -> Unit
        InkModelState.Downloading -> Text(
            text = "日本語認識モデルを準備中...",
            color = MaterialTheme.colorScheme.secondary,
        )
        InkModelState.Ready -> Text(
            text = "日本語認識モデル準備完了",
            color = Color(0xFF047857),
        )
        is InkModelState.Error -> Text(
            text = modelState.message,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private data class RubyToken(
    val text: String,
    val ruby: String = "",
    val isTarget: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RubySentence(
    sentence: String,
    sentenceReading: String,
    markedSentence: String = "",
    markedSentenceReading: String = "",
    targetText: String,
    targetReading: String = targetText,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    targetColor: Color = Color(0xFF2563EB),
    textSize: TextUnit = 36.sp,
    showTargetRuby: Boolean = true,
) {
    val tokens = remember(
        sentence,
        sentenceReading,
        markedSentence,
        markedSentenceReading,
        targetText,
        targetReading,
        showTargetRuby,
    ) {
        rubyTokens(
            sentence = sentence,
            sentenceReading = sentenceReading,
            markedSentence = markedSentence,
            markedSentenceReading = markedSentenceReading,
            targetText = targetText,
            targetReading = targetReading,
            showTargetRuby = showTargetRuby,
        )
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tokens.forEachIndexed { index, token ->
            RubyTokenText(
                token = token,
                textColor = textColor,
                targetColor = targetColor,
                textSize = textSize,
                modifier = Modifier.padding(end = if (index == tokens.lastIndex) 0.dp else 1.dp),
            )
        }
    }
}

@Composable
private fun RubyTokenText(
    token: RubyToken,
    textColor: Color,
    targetColor: Color,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val bodyColor = if (token.isTarget) targetColor else textColor
    val bodyWeight = if (token.isTarget) FontWeight.Bold else FontWeight.Normal
    val rubyText = token.ruby.ifEmpty { "　" }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            text = rubyText,
            fontSize = 15.sp,
            lineHeight = 15.sp,
            color = if (token.ruby.isBlank()) Color.Transparent else MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = token.text,
            fontSize = textSize,
            lineHeight = 45.sp,
            color = bodyColor,
            fontWeight = bodyWeight,
        )
    }
}

private fun rubyTokens(
    sentence: String,
    sentenceReading: String,
    markedSentence: String,
    markedSentenceReading: String,
    targetText: String,
    targetReading: String,
    showTargetRuby: Boolean,
): List<RubyToken> {
    if (sentence.isBlank()) return emptyList()
    explicitRubyTokens(
        markedSentence = markedSentence,
        markedSentenceReading = markedSentenceReading,
        targetText = targetText,
        targetReading = targetReading,
        showTargetRuby = showTargetRuby,
    )
        ?.let { return it.mergePlainNeighbors() }
    if (sentenceReading.isBlank()) return targetOnlyTokens(sentence, targetText)

    val targetStart = sentence.indexOf(targetText).takeIf { it >= 0 && targetText.isNotEmpty() }
    val targetRange = targetStart?.let { it until it + targetText.length } ?: IntRange.EMPTY
    val tokens = mutableListOf<RubyToken>()
    var sentenceIndex = 0
    var readingIndex = 0

    while (sentenceIndex < sentence.length) {
        val char = sentence[sentenceIndex]
        if (!char.isKanji()) {
            tokens.add(RubyToken(char.toString()))
            if (readingIndex < sentenceReading.length && sentenceReading[readingIndex].matchesReadingAnchor(char)) {
                readingIndex++
            }
            sentenceIndex++
            continue
        }

        val runStart = sentenceIndex
        while (
            sentenceIndex < sentence.length &&
            sentence[sentenceIndex].isKanji()
        ) {
            sentenceIndex++
        }
        val runEnd = sentenceIndex
        val runText = sentence.substring(runStart, runEnd)
        val nextAnchor = sentence.drop(sentenceIndex).firstOrNull { !it.isKanji() }
        val rubyEnd = if (nextAnchor == null) {
            sentenceReading.length
        } else {
            sentenceReading.indexOfReadingAnchor(nextAnchor, startIndex = readingIndex)
                .takeIf { it >= readingIndex }
                ?: readingIndex
        }
        val runReading = sentenceReading.substring(readingIndex, rubyEnd)
        readingIndex = rubyEnd

        if (targetStart != null && targetStart in runStart until runEnd) {
            tokens.addTargetRunTokens(
                runText = runText,
                runReading = runReading,
                targetOffset = targetStart - runStart,
                targetText = targetText,
                targetReading = targetReading,
                showTargetRuby = showTargetRuby,
            )
        } else {
            tokens.add(RubyToken(text = runText, ruby = runReading))
        }
    }

    return tokens.mergePlainNeighbors()
}

private data class MarkedSegment(
    val text: String,
    val marker: Char? = null,
)

private fun explicitRubyTokens(
    markedSentence: String,
    markedSentenceReading: String,
    targetText: String,
    targetReading: String,
    showTargetRuby: Boolean,
): List<RubyToken>? {
    if (!markedSentence.hasRubyMarkers() || !markedSentenceReading.hasRubyMarkers()) return null

    val sentenceSegments = markedSentence.toMarkedSegments() ?: return null
    val readingSegments = markedSentenceReading.toMarkedSegments() ?: return null
    val readingMarkedSegments = readingSegments.filter { it.marker != null }
    var readingMarkerIndex = 0

    return buildList {
        sentenceSegments.forEach { sentenceSegment ->
            when (sentenceSegment.marker) {
                '[' -> {
                    val readingSegment = readingMarkedSegments.getOrNull(readingMarkerIndex)
                    if (readingSegment?.marker != '[') return null
                    readingMarkerIndex++
                    add(
                        RubyToken(
                            text = sentenceSegment.text,
                            ruby = targetReading
                                .takeIf { showTargetRuby && sentenceSegment.text == targetText }
                                .orEmpty(),
                            isTarget = true,
                        ),
                    )
                }
                '{' -> {
                    val readingSegment = readingMarkedSegments.getOrNull(readingMarkerIndex)
                    if (readingSegment?.marker != '{') return null
                    readingMarkerIndex++
                    add(RubyToken(sentenceSegment.text, ruby = readingSegment.text))
                }
                else -> add(RubyToken(sentenceSegment.text))
            }
        }
        if (readingMarkerIndex != readingMarkedSegments.size) return null
    }
}

private fun String.hasRubyMarkers(): Boolean =
    any { it == '[' || it == '{' }

private fun String.toMarkedSegments(): List<MarkedSegment>? {
    val segments = mutableListOf<MarkedSegment>()
    val plain = StringBuilder()
    var index = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            segments.add(MarkedSegment(plain.toString()))
            plain.clear()
        }
    }

    while (index < length) {
        val char = this[index]
        val closeMarker = when (char) {
            '[' -> ']'
            '{' -> '}'
            ']', '}' -> return null
            else -> null
        }

        if (closeMarker == null) {
            plain.append(char)
            index++
            continue
        }

        flushPlain()
        val closeIndex = indexOf(closeMarker, startIndex = index + 1)
        if (closeIndex < 0) return null
        segments.add(
            MarkedSegment(
                text = substring(index + 1, closeIndex),
                marker = char,
            ),
        )
        index = closeIndex + 1
    }
    flushPlain()
    return segments
}

private fun MutableList<RubyToken>.addTargetRunTokens(
    runText: String,
    runReading: String,
    targetOffset: Int,
    targetText: String,
    targetReading: String,
    showTargetRuby: Boolean,
) {
    val prefixText = runText.take(targetOffset)
    val suffixText = runText.drop(targetOffset + targetText.length)
    val targetReadingStart = runReading.indexOf(targetReading)

    if (targetReadingStart < 0) {
        if (prefixText.isNotEmpty()) add(RubyToken(prefixText, runReading))
        add(RubyToken(targetText, ruby = targetReading.takeIf { showTargetRuby }.orEmpty(), isTarget = true))
        if (suffixText.isNotEmpty()) add(RubyToken(suffixText))
        return
    }

    val targetReadingEnd = targetReadingStart + targetReading.length
    val prefixReading = runReading.take(targetReadingStart)
    val suffixReading = runReading.drop(targetReadingEnd)

    if (prefixText.isNotEmpty()) {
        add(RubyToken(prefixText, prefixReading))
    }
    add(RubyToken(targetText, ruby = targetReading.takeIf { showTargetRuby }.orEmpty(), isTarget = true))
    if (suffixText.isNotEmpty()) {
        add(RubyToken(suffixText, suffixReading))
    }
}

private fun targetOnlyTokens(sentence: String, targetText: String): List<RubyToken> {
    val targetStart = sentence.indexOf(targetText)
    if (targetStart < 0 || targetText.isEmpty()) {
        return listOf(RubyToken(sentence))
    }
    return buildList {
        if (targetStart > 0) add(RubyToken(sentence.substring(0, targetStart)))
        add(RubyToken(sentence.substring(targetStart, targetStart + targetText.length), isTarget = true))
        if (targetStart + targetText.length < sentence.length) {
            add(RubyToken(sentence.substring(targetStart + targetText.length)))
        }
    }
}

private fun List<RubyToken>.mergePlainNeighbors(): List<RubyToken> {
    val merged = mutableListOf<RubyToken>()
    forEach { token ->
        val last = merged.lastOrNull()
        if (
            last != null &&
            last.ruby.isEmpty() &&
            !last.isTarget &&
            token.ruby.isEmpty() &&
            !token.isTarget
        ) {
            merged[merged.lastIndex] = last.copy(text = last.text + token.text)
        } else {
            merged.add(token)
        }
    }
    return merged
}

private fun Char.isKanji(): Boolean = this in '\u4E00'..'\u9FFF'

private fun String.indexOfReadingAnchor(anchor: Char, startIndex: Int): Int {
    for (index in startIndex until length) {
        if (this[index].matchesReadingAnchor(anchor)) return index
    }
    return -1
}

private fun Char.matchesReadingAnchor(anchor: Char): Boolean =
    this == anchor || this.toHiragana() == anchor.toHiragana()

private fun Char.toHiragana(): Char =
    if (this in '\u30A1'..'\u30F6') {
        this - 0x60
    } else {
        this
    }

@Composable
private fun highlightedSentence(
    fullSentence: String,
    targetText: String,
    highlightColor: Color = Color(0xFF2563EB),
) = buildAnnotatedString {
    val startIndex = fullSentence.indexOf(targetText)
    if (startIndex < 0 || targetText.isEmpty()) {
        append(fullSentence)
        return@buildAnnotatedString
    }

    append(fullSentence.substring(0, startIndex))
    withStyle(
        SpanStyle(
            color = highlightColor,
            fontWeight = FontWeight.Bold,
        ),
    ) {
        append(targetText)
    }
    append(fullSentence.substring(startIndex + targetText.length))
}
