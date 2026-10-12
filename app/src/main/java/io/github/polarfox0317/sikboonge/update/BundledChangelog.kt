package io.github.polarfox0317.sikboonge.update

/** Offline post-update notes; acknowledging the dialog supplies the new last-seen code. */
internal fun pendingBundledChangelog(
    currentCode: Long,
    lastSeenCode: Long,
    isUpdatedInstall: Boolean,
): List<ReleaseNoteBlock>? {
    if (currentCode <= lastSeenCode || (!isUpdatedInstall && lastSeenCode == 0L)) return null
    return bundledChangelog(currentCode)
}

internal fun bundledChangelog(
    versionCode: Long,
    preferredLanguage: String? = null,
): List<ReleaseNoteBlock>? = when (versionCode) {
    109L, 110L -> listOf(
        ReleaseNoteBlock.Item("봇 연결 후 검색과 영상 목록 화면으로 바로 이동하도록 수정했어요."),
        ReleaseNoteBlock.Item("연결이 끊기거나 재연결 중이어도 기존에 받은 영상 목록을 볼 수 있어요."),
        ReleaseNoteBlock.Item("봇에서 저장한 오디오가 변환 탭의 추출 완료 화면에 나타나는 문제를 수정했어요."),
    )
    111L -> listOf(
        ReleaseNoteBlock.Heading("다운로드 알림 개선"),
        ReleaseNoteBlock.Item("다운로드 중인 오디오를 보관함 목록에서 확인할 수 있어요.", depth = 1),
        ReleaseNoteBlock.Item("진행률, 다운로드 속도, 예상 남은 시간을 보관함과 시스템 알림에 표시해요.", depth = 1),
        ReleaseNoteBlock.Item("보관함과 시스템 알림에서 다운로드를 중지할 수 있어요.", depth = 1),
        ReleaseNoteBlock.Item("다운로드가 완료되면 시스템 알림으로 알려줘요.", depth = 1),
        ReleaseNoteBlock.Heading("업데이트 안내 개선"),
        ReleaseNoteBlock.Item("언어에 맞는 변경사항을 목록으로 정리해 읽기 편하게 개선했어요.", depth = 1),
    )
    125L -> parseReleaseNotes(
        body = CHANGELOG_1_1_0,
        preferredLanguage = preferredLanguage,
        releaseTitle = "싴붕이 1.1.0",
        version = "1.1.0",
        releaseUrl = null,
    ).blocks
    132L -> parseReleaseNotes(
        body = CHANGELOG_1_1_1,
        preferredLanguage = preferredLanguage,
        releaseTitle = "싴붕이 1.1.1",
        version = "1.1.1",
        releaseUrl = null,
    ).blocks
    else -> null
}

// Approved bilingual release notes. Keep category order and wording unchanged.
private val CHANGELOG_1_1_0 = """
    # 싴붕이 1.1.0

    ## 한국어

    ### 다운로드 기능 개선

    - 봇 영상을 저장하는 동안에도 다른 영상의 저장 버튼을 사용할 수 있어요.
    - 설정에서 저장 방식을 선택할 수 있어요. `차례로 저장`은 저장 버튼을 누른 순서대로 한 건씩, `동시에 저장`은 최대 3건을 함께 다운로드해요.
    - 보관함과 시스템 알림에서 다운로드를 일시 중지하고 다시 진행할 수 있어요.
    - 차례로 저장할 때 일시 중지한 항목은 건너뛰고 다음 항목을 진행해요. 재개한 항목은 원래 요청 순서로 돌아가며, 진행 중인 다운로드는 유지해요.
    - 중복된 진행 요약 알림을 정리하고, 저장이 완료되면 완료된 파일 이름으로 알려줘요.
    - 다운로드 취소 후 진행 알림이 남거나 다시 나타나던 문제를 수정했어요.

    ### 태그와 검색

    - 보관함의 오디오와 봇 영상에 태그를 추가하고 편집할 수 있어요.
    - 검색창에서 제목과 태그를 함께 검색하고, `#태그`로 태그만 검색할 수 있어요.
    - 태그 필터로 원하는 항목을 모아 볼 수 있어요.

    ### 테마

    - 설정에서 라이트 모드, 다크 모드, 시스템 설정 따르기를 선택할 수 있어요.

    ### 첫 사용 가이드

    - 처음 설치했을 때 앱 설정과 봇 연결을 안내하는 환영 가이드를 추가했어요.
    - 봇 연결도 가이드 안에서 진행할 수 있고, 나중에 설정할 수도 있어요.

    ### 오픈소스 라이선스

    - 설정에 오픈소스 라이선스 목록과 상세 화면을 추가했어요.
    - 사용한 라이브러리의 버전, 용도, 저작권 고지와 라이선스 원문을 확인할 수 있어요. 원문은 오프라인에서도 읽고 복사할 수 있어요.

    ### 버그 수정

    - 봇 연결이 끊겼을 때 새로고침으로 다시 연결을 시도하도록 수정했어요.
    - 다운로드 시작 안내가 준비 상태 전환 직후 사라지던 문제를 수정했어요.

    ## English

    ### Download improvements

    - You can save other bot videos while a save is already in progress.
    - Choose a save mode in Settings. Sequential mode downloads one item at a time in the order you tap Save; parallel mode downloads up to three items together.
    - Pause and resume downloads from the Library or system notifications.
    - Sequential mode skips paused items and continues with the next download. Resumed items return to their original queue order without interrupting an active download.
    - Removed the redundant progress summary notification. Successful saves now show a completion notification with the saved file's name.
    - Fixed progress notifications remaining or reappearing after a download was cancelled.

    ### Tags and search

    - Add and edit tags for Library audio and bot videos.
    - Search titles and tags together, or use `#tag` to search tags only.
    - Use tag filters to find the items you want.

    ### Themes

    - Choose light mode, dark mode, or follow the system theme in Settings.

    ### First-use guide

    - A new welcome guide helps you configure the app and connect your bot after a fresh installation.
    - Connect your bot within the guide, or choose to set it up later.

    ### Open-source licenses

    - Added an open-source license list and detail pages in Settings.
    - View library versions, descriptions, copyright notices, and original license texts. License texts are available offline and can be copied.

    ### Bug fixes

    - Refresh now retries the bot connection when disconnected.
    - Fixed the download-start message disappearing immediately after preparation finished.
""".trimIndent()

// Approved bilingual release notes. Keep category order and wording unchanged.
private val CHANGELOG_1_1_1 = """
    # 싴붕이 1.1.1

    ## 한국어

    ### 재생 기능 개선
    - Android 미디어 컨트롤에 이전·다음 항목과 10초 앞뒤 이동 버튼을 추가했어요.

    ### 제목 편집 개선
    - 영상 제목의 120자 제한을 제거했어요.
    - 긴 영상 제목을 편집할 때 입력란의 폭을 충분히 사용하고, 글자 단위로 줄바꿈되도록 개선했어요.

    ### 버그 수정
    - `재생`을 누르면 선택한 파일만 재생해야 하는데 기존 재생목록에 추가되던 문제를 수정했어요.
    - 재생 중인 항목을 재생목록에서 삭제해도 재생이 계속되던 문제를 수정했어요.
    - 재생이 끝난 뒤 재생목록의 다음 항목으로 이어지지 않던 문제를 수정했어요.
    - 긴 파일 이름과 검색어를 편집할 때 커서를 좌우 끝에 잡고 있어도 스크롤되지 않던 문제를 수정했어요.
    - 업데이트 다운로드 완료 알림을 눌러도 설치 화면이 열리지 않던 문제를 수정했어요.

    ## English

    ### Playback improvements
    - Added previous, next, and 10-second backward and forward controls to Android media controls.

    ### Title editing improvements
    - Removed the 120-character limit for video titles.
    - Long video titles now use the available input width and wrap at character boundaries while editing.

    ### Bug fixes
    - Fixed `Play` appending the selected file to the existing queue instead of replacing it.
    - Fixed playback continuing after the currently playing item was removed from the queue.
    - Fixed playback stopping instead of continuing to the next item in the queue.
    - Fixed long filenames and search queries not scrolling while holding the cursor handle at either edge.
    - Fixed the installer not opening when tapping the update download completion notification.
""".trimIndent()
