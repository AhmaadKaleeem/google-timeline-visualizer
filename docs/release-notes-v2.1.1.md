# Timeline Visualizer 2.1.1

This reliability update fixes crashes when importing large Timeline JSON files. It
reduces peak memory without changing Timeline points, filtering, route distances,
or rendered paths. If an automatic import is interrupted, the app remains usable
and asks you to choose the file again instead of repeating the failure.

## 한국어

대용량 Timeline JSON 파일을 불러올 때 앱이 종료되는 문제를 수정했습니다. Timeline
위치, 필터링, 이동 거리와 표시 경로는 바뀌지 않으며, 자동 불러오기가 중단되면 같은
실패를 반복하지 않고 파일을 다시 선택하도록 안내합니다.

## 日本語

大きな Timeline JSON ファイルの読み込み時にアプリが終了する問題を修正しました。
位置情報、フィルタリング、移動距離、表示経路は変わりません。自動読み込みが中断した
場合は、失敗を繰り返さず、ファイルを選び直すよう案内します。

## 简体中文

修复了导入大型 Timeline JSON 文件时应用退出的问题。地点、筛选、行程距离和显示路线
保持不变。如果自动导入中断，应用会要求重新选择文件，而不会重复失败。

## 繁體中文

修正匯入大型 Timeline JSON 檔案時應用程式結束的問題。位置、篩選、旅程距離和顯示路線
維持不變。若自動匯入中斷，應用程式會要求重新選擇檔案，而不會重複失敗。

## Español

Corrige el cierre de la aplicación al importar archivos JSON de Timeline grandes.
Los puntos, filtros, distancias y rutas no cambian. Si una importación automática se
interrumpe, la aplicación solicita elegir el archivo de nuevo en lugar de repetir el fallo.

## Français

Corrige la fermeture de l’application lors de l’importation de grands fichiers JSON
Timeline. Les points, filtres, distances et tracés restent identiques. Si un import
automatique est interrompu, l’application demande de choisir à nouveau le fichier.

## Deutsch

Behebt App-Abstürze beim Import großer Timeline-JSON-Dateien. Punkte, Filter,
Entfernungen und dargestellte Routen bleiben unverändert. Nach einem unterbrochenen
automatischen Import fordert die App zur erneuten Dateiauswahl auf.

## Português (Brasil)

Corrige o encerramento do aplicativo ao importar arquivos JSON grandes da Timeline.
Pontos, filtros, distâncias e rotas permanecem iguais. Se uma importação automática
for interrompida, o aplicativo solicitará que o arquivo seja selecionado novamente.

This release addresses issue #44. Timeline processing remains local, and no Timeline
content is logged or uploaded.
