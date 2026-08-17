# Timeline Visualizer 1.2.0

## English

This release makes Timeline Visualizer faster and clearer from preview to finished
video. Scrolling now reuses the current map frame instead of repeatedly rebuilding
the full route, while preview and video creation share cached route calculations.

Titles can use the reusable `{year}` and `{name}` placeholders. Your template is
saved for future videos, and the preview updates after a short typing pause or when
you leave the field. The main actions are now named **Load Timeline**, **Preview**,
and **Create video** to make their purpose clearer.

Video creation now shows its current stage, progress, and an estimated time once
enough work has been measured. You can cancel safely; incomplete output is removed.
When the video is ready, you can watch it in your phone's video player, share it,
or start another one.

### Install

Download `TimelineVisualizer-v1.2.0.apk` below and open it on an Android 8.0 or
newer phone. If Android blocks the installation, open the prompt's **Settings**,
allow the browser or file app you used to **Install unknown apps**, then return and
install the APK. You may turn that permission off again afterward.

Only download the APK from this GitHub repository. Existing users can install this
version over the previous release without uninstalling it.

To obtain your data, use **Phone Settings → Location → Location services → Timeline
→ Export Timeline data**. Save `Timeline.json`, then choose **Load Timeline** in the
app. Google sign-in and location permission are not required.

## 한국어

이번 릴리스에서는 미리보기부터 완성 영상까지 더 빠르고 이해하기 쉽게 개선했습니다.
화면을 스크롤할 때 전체 경로를 매번 다시 계산하지 않고 현재 지도 화면을 재사용하며,
미리보기와 영상 만들기에서도 준비된 경로 계산을 함께 활용합니다.

제목에는 재사용 가능한 `{year}`와 `{name}`을 넣을 수 있습니다. 템플릿은 다음 영상을
위해 저장되고, 입력을 잠시 멈추거나 입력란을 벗어나면 미리보기에 반영됩니다. 주요
버튼도 용도에 맞게 **타임라인 불러오기**, **미리보기**, **영상 만들기**로 다듬었습니다.

영상을 만들 때 현재 단계와 진행률을 보여 주고, 충분한 진행 정보가 쌓이면 예상 남은
시간도 표시합니다. 도중에 안전하게 취소할 수 있으며 완성되지 않은 파일은 삭제됩니다.
완성 후에는 휴대전화의 영상 앱으로 보거나, 공유하거나, 다른 영상을 만들 수 있습니다.

### 설치

아래에서 `TimelineVisualizer-v1.2.0.apk`를 다운로드해 Android 8.0 이상 휴대전화에서
여세요. 설치가 차단되면 안내 화면의 **설정**을 열고, 사용한 브라우저 또는 파일 앱에
**출처를 알 수 없는 앱 설치**를 허용한 뒤 돌아와 APK를 설치하세요. 설치 후에는 이
권한을 다시 꺼도 됩니다.

APK는 반드시 이 GitHub 저장소에서만 다운로드하세요. 기존 사용자는 앱을 삭제하지
않고 이전 버전 위에 업데이트할 수 있습니다.

데이터는 **휴대전화 설정 → 위치 → 위치 서비스 → 타임라인 → 타임라인 데이터
내보내기**에서 받습니다. `Timeline.json`을 저장한 뒤 앱에서 **타임라인 불러오기**를
누르세요. Google 로그인과 위치 권한은 필요하지 않습니다.
