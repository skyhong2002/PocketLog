# 手機活動日誌工具：程式碼核對

核對日期：2026-11-14。依當時 GitHub 主分支原始碼與官方技術文件；未安裝或實機量測。

「未見內建」只表示核對的核心路徑未找到，並非證明所有版本／外掛永遠不可能支援。「原始碼支援」不等於已驗證最新 Android 可正常使用。

重要更正：AWARE 目前原始碼包含定時手機截圖、保存與同步接線；先前只描述為感測框架不夠完整。Omi 亦非所有處理都在後端，已有部分裝置端語音處理路徑。

## ActivityWatch Android

適合參考事件擷取與統計，不能直接替代我們的截圖內容理解。

來源版本：`284be9a4688bf132e679b55bc03c183a5d95e72f`

- **平台**：Android。
- **輸入來源**：系統使用事件；可選網址／媒體資訊。
- **手機自動截圖**：未見內建。已查核心 watcher：以 UsageStats、無障礙服務及 MediaSession 取得事件，未見定時截圖路徑。
- **畫面內容理解**：未見內建。核心並非用視覺模型讀懂畫面。
- **AI 摘要**：非核心功能。官方文件有把統計交給外部 AI 分析的工作流，不等同內建自動活動摘要。
- **分類方式**：規則比對。以 app 名稱與標題做正規表示式分類；不是畫面語意分類。
- **感受紀錄**：未確認。核對的核心紀錄與分類路徑未確認有心情自填流程。
- **運算位置**：手機端收集／本機服務；可同步。
- **資料位置**：本機事件資料庫。
- **授權**：MPL-2.0。
- **App 使用事件**：自動。UsageStatsManager 讀取前景 app、時間與 session。
- **網頁資訊**：條件支援。無障礙服務讀取支援瀏覽器的網址／標題，效果依瀏覽器而異。
- **音訊**：媒體資訊。記錄播放器提供的標題、作者、播放狀態；不是錄音。
- **時間軸**：使用時間軸。以 app／網站等事件呈現。
- **搜尋**：事件查詢。可查事件與統計，不是視覺內容的語意搜尋。

程式碼依據：

- [App 使用事件](https://github.com/ActivityWatch/aw-android/blob/284be9a4688bf132e679b55bc03c183a5d95e72f/mobile/src/main/java/net/activitywatch/android/watcher/UsageStatsWatcher.kt)
- [網頁資訊](https://github.com/ActivityWatch/aw-android/blob/284be9a4688bf132e679b55bc03c183a5d95e72f/mobile/src/main/java/net/activitywatch/android/watcher/WebWatcher.kt)
- [媒體事件](https://github.com/ActivityWatch/aw-android/blob/284be9a4688bf132e679b55bc03c183a5d95e72f/mobile/src/main/java/net/activitywatch/android/watcher/MediaWatcher.kt)
- [分類規則文件](https://github.com/ActivityWatch/docs/blob/master/src/features/categorization.rst)

## Memex

最接近「AI 整理生活片段」，但其資料來源不是全天被動手機截圖。

來源版本：`d541b1b5ce3bc69c71037fc27819959aacab27fe`

- **平台**：Android / iOS。
- **輸入來源**：文字、照片、語音、匯入／分享。
- **手機自動截圖**：未見內建。已查 Android manifest、輸入／相簿與 agent 路徑，未見全天自動擷取其他 app 畫面的管線。相簿照片建議不等於自動截圖。
- **畫面內容理解**：輸入圖片。可交由支援多模態的模型理解使用者提供的圖片；不是自動讀取全天畫面。
- **AI 摘要**：AI 整理。模型把生活片段整理成卡片、摘要與洞察。
- **分類方式**：AI 標籤／卡片。manage_timeline_card 由模型選擇卡片模板及標籤；不等同全天活動分類。
- **感受紀錄**：可文字記感受。能提供感受作為日誌內容；未確認有標準化每日心情量表。
- **運算位置**：手機編排 agent；推論依設定的模型端點。
- **資料位置**：本機檔案／SQLite；模型請求可出網。
- **授權**：GPL-3.0。
- **App 使用事件**：未見內建。未見以 UsageStats 自動記錄其他 app 時數的核心管線。
- **網頁資訊**：使用者提供連結。可整理使用者提供的連結；不是自動瀏覽歷史追蹤。
- **音訊**：使用者語音。由使用者提供語音片段。
- **時間軸**：AI 卡片時間軸。根據使用者提供或匯入的紀錄建立。
- **搜尋**：知識／紀錄查找。README 與 agent 系統提供知識整理、搜尋及問答。

程式碼依據：

- [輸入來源與資料流](https://github.com/memex-lab/memex/blob/d541b1b5ce3bc69c71037fc27819959aacab27fe/README.md)
- [Android 權限與服務](https://github.com/memex-lab/memex/blob/d541b1b5ce3bc69c71037fc27819959aacab27fe/android/app/src/main/AndroidManifest.xml)
- [AI 建立卡片與標籤](https://github.com/memex-lab/memex/blob/d541b1b5ce3bc69c71037fc27819959aacab27fe/lib/agent/skills/manage_timeline_card/timeline_card_skill.dart)
- [相簿照片建議](https://github.com/memex-lab/memex/blob/d541b1b5ce3bc69c71037fc27819959aacab27fe/lib/data/services/photo_suggestion_service.dart)

## Omi

值得借鏡音訊到記憶的後端；不能稱為已確認的手機版 Dayflow。

來源版本：`c40fda41b0670fb89ed7237807d71450ad705890`

- **平台**：Android / iOS；另有桌面／穿戴裝置。
- **輸入來源**：手機麥克風、穿戴裝置音訊；桌面另有畫面。
- **手機自動截圖**：手機未確認。核對到的手機 capture 是麥克風／BLE 音訊，未確認全天手機畫面擷取。桌面畫面功能不能套用到手機。
- **畫面內容理解**：桌面有。專案有桌面畫面與會議截圖處理；手機端未確認同樣能力。
- **AI 摘要**：對話摘要。後端以 LLM 處理對話、摘要、待辦及記憶。
- **分類方式**：對話結構化。模型整理對話與任務；不是已確認的手機畫面活動分類。
- **感受紀錄**：未確認。未確認有等同每日心情量表的手機自填流程。
- **運算位置**：雲端／後端為主；另有裝置端轉錄路徑。
- **資料位置**：手機音訊暫存＋後端；依模式不同。
- **授權**：MIT。
- **App 使用事件**：手機未確認。未確認有 Android 全 app 使用事件的核心日誌。
- **網頁資訊**：手機未確認。未確認手機自動追蹤其他瀏覽器內容。
- **音訊**：核心來源。手機麥克風／BLE 音訊收集，可即時或批次處理。
- **時間軸**：對話／記憶列表。以對話及記憶為主。
- **搜尋**：記憶搜尋／問答。可搜尋和詢問過去對話與記憶。

程式碼依據：

- [手機擷取架構](https://github.com/BasedHardware/omi/blob/c40fda41b0670fb89ed7237807d71450ad705890/app/lib/services/capture/CAPTURE_ARCHITECTURE.md)
- [手機麥克風來源](https://github.com/BasedHardware/omi/blob/c40fda41b0670fb89ed7237807d71450ad705890/app/lib/services/audio_sources/phone_mic_source.dart)
- [裝置端處理的限制與開關](https://github.com/BasedHardware/omi/blob/c40fda41b0670fb89ed7237807d71450ad705890/app/lib/services/on_device/README.md)
- [後端 LLM 結構化](https://github.com/BasedHardware/omi/blob/c40fda41b0670fb89ed7237807d71450ad705890/backend/utils/llm/conversation_processing.py)

## MemoryVoid

擷取 API 很值得參考，但它是快速手動記憶工具。

來源版本：`5e66282c6119c3e3ac7f8eccc4cab2c6595aa00f`

- **平台**：Android。
- **輸入來源**：硬體鍵／助理手勢觸發截圖＋語音。
- **手機自動截圖**：手動觸發。AccessibilityService.takeScreenshot；由硬體鍵／助理動作觸發，非全天定時截圖。不同模式可在開始或結束時擷取。
- **畫面內容理解**：未見內建。已核對擷取服務與主要介面，未見把圖片交給視覺模型理解的管線。
- **AI 摘要**：未見內建。核心保存語音／截圖／文字筆記，未見 AI 摘要管線。
- **分類方式**：手動標籤。介面由使用者建立與選取分類。
- **感受紀錄**：可附語音／筆記。能主動記下感受；未確認專用心情量表。
- **運算位置**：手機端擷取與保存。
- **資料位置**：本機圖片、語音與紀錄。
- **授權**：MIT。
- **App 使用事件**：未見內建。不是 app 使用時數追蹤器。
- **網頁資訊**：手動截取畫面。可保存目前畫面，未見自動瀏覽歷史管線。
- **音訊**：手動錄音。依按鍵動作開始與停止。
- **時間軸**：記憶卡片。保存手動捕捉的片刻。
- **搜尋**：未確認。未確認內容語意搜尋。

程式碼依據：

- [按鍵、錄音、截圖](https://github.com/dhruvpitroda20-code/MemoryVoid/blob/5e66282c6119c3e3ac7f8eccc4cab2c6595aa00f/app/src/main/java/com/example/essentialspace/KeyMappingService.kt)
- [手動分類與筆記](https://github.com/dhruvpitroda20-code/MemoryVoid/blob/5e66282c6119c3e3ac7f8eccc4cab2c6595aa00f/app/src/main/java/com/example/essentialspace/MainActivity.kt)

## Mindful

是數位習慣管理工具，和自動內容日誌差異很大。

來源版本：`b6eb68d500463c54e1127b6cc7ad540f02ae0c8e`

- **平台**：Android。
- **輸入來源**：系統使用統計＋無障礙 UI 事件。
- **手機自動截圖**：未見內建。核對的主要擷取管線不是截圖。
- **畫面內容理解**：未見內建。以 app／UI 節點判斷短影片等情境，並非視覺模型讀畫面。
- **AI 摘要**：未見內建。主要是使用統計、專注、封鎖與提醒。
- **分類方式**：規則／使用者設定。依 app、限制群組與特定 UI 規則處理，不是 AI 內容分類。
- **感受紀錄**：未確認。wellbeing 定位不代表已內建心情日誌。
- **運算位置**：手機端統計、規則與限制。
- **資料位置**：本機；README 宣告可離線使用。
- **授權**：GPL-2.0。
- **App 使用事件**：自動。UsageStatsManager 取得前景使用時長。
- **網頁資訊**：條件辨識。無障礙節點識別瀏覽器／特定介面，不等同全文理解。
- **音訊**：未見核心錄音。已查核心擷取路徑未見用全天音訊建立日誌。
- **時間軸**：使用／專注紀錄。可回看使用量與專注 session。
- **搜尋**：統計查閱。未見跨畫面內容語意搜尋。

程式碼依據：

- [App 使用統計](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/helpers/usages/AppsUsageHelper.kt)
- [前景 app 事件](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/services/accessibility/TrackingManager.kt)
- [短影片介面規則](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/services/accessibility/ShortsPlatformManager.kt)

## Daily You

適合借鏡心情輸入與日記回顧，不能替代被動活動擷取。

來源版本：`f2dc0efa51e55bdfb169118dab8ba86febd59968`

- **平台**：Android。
- **輸入來源**：使用者輸入日記、心情、標籤與附件。
- **手機自動截圖**：未見內建。核對輸入、資料模型與 manifest，未見自動擷取其他 app 畫面；repo 的 screenshots 含商店／測試圖片，不能當作記錄能力。
- **畫面內容理解**：未見內建。核心是使用者自己記錄。
- **AI 摘要**：未見內建。已查紀錄與統計路徑，未見自動 LLM 摘要。
- **分類方式**：手動標籤／分類。使用者選取標籤、分類與追蹤值。
- **感受紀錄**：明確支援。Entry 有 mood 欄位，編輯頁提供心情選取。
- **運算位置**：手機端編輯、儲存與統計。
- **資料位置**：本機／自選儲存；離線使用。
- **授權**：GPL-3.0＋額外條款。
- **App 使用事件**：未見內建。不是 app 使用紀錄器。
- **網頁資訊**：未見內建。不是瀏覽器監測器。
- **音訊**：手動附件。目前編輯頁可開啟 audio_recorder_page 加入音訊附件。
- **時間軸**：日記時間軸。依日記紀錄回顧。
- **搜尋**：日記查閱／篩選。可回看日記；此處未確認語意搜尋。

程式碼依據：

- [日記與 mood 資料模型](https://github.com/Demizo/Daily_You/blob/f2dc0efa51e55bdfb169118dab8ba86febd59968/lib/models/entry.dart)
- [編輯、心情、標籤與附件](https://github.com/Demizo/Daily_You/blob/f2dc0efa51e55bdfb169118dab8ba86febd59968/lib/pages/edit_entry_page.dart)
- [統計計算](https://github.com/Demizo/Daily_You/blob/f2dc0efa51e55bdfb169118dab8ba86febd59968/lib/stats/entry_stats.dart)
- [完整授權條款](https://github.com/Demizo/Daily_You/blob/f2dc0efa51e55bdfb169118dab8ba86febd59968/ADDITIONAL_TERMS.md)

## Beiwe

可參考採樣、問卷與上傳排程；不是手機畫面內容回顧產品。

來源版本：`5eb833ce413fc3b7c52e1e2790b01c8d584df028`

- **平台**：Android / iOS（不同 app）；有後端。
- **輸入來源**：感測器、通訊紀錄（依版本）、問卷／錄音。
- **手機自動截圖**：未見內建。核對 Android listeners、manifest 與研究平台資料流，未見全天螢幕截圖理解管線。
- **畫面內容理解**：未見內建。核心是原始感測資料與問卷。
- **AI 摘要**：非日誌摘要。資料可交給後端／Forest 做研究分析，非現成 AI 每日生活摘要。
- **分類方式**：感測資料研究分析。不是從手機畫面判斷工作／娛樂的分類器。
- **感受紀錄**：可設問卷。研究者能設計自述／心情問卷；不是現成消費型日記。
- **運算位置**：手機採樣、加密暫存／上傳；後端分析。
- **資料位置**：手機加密檔案＋研究伺服器。
- **授權**：BSD-3-Clause（Android app）。
- **App 使用事件**：非主要資料流。核對到的核心包含感測／通訊／螢幕狀態，不應等同完整 app 內容日誌。
- **網頁資訊**：未見內建。未確認其他瀏覽器頁面內容擷取。
- **音訊**：依研究設定。支援主動錄音及其他可設定的音訊採樣。
- **時間軸**：研究資料流。不是完成的生活活動卡片介面。
- **搜尋**：研究端查詢。不是現成日誌語意搜尋。

程式碼依據：

- [GPS 採樣](https://github.com/onnela-lab/beiwe-android/blob/5eb833ce413fc3b7c52e1e2790b01c8d584df028/app/src/main/java/org/beiwe/app/listeners/GPSListener.kt)
- [問卷答案保存](https://github.com/onnela-lab/beiwe-android/blob/5eb833ce413fc3b7c52e1e2790b01c8d584df028/app/src/main/java/org/beiwe/app/survey/SurveyAnswersRecorder.kt)
- [Wi-Fi／行動網路上傳條件](https://github.com/onnela-lab/beiwe-android/blob/5eb833ce413fc3b7c52e1e2790b01c8d584df028/app/src/main/java/org/beiwe/app/networking/NetworkUtility.kt)
- [權限與服務](https://github.com/onnela-lab/beiwe-android/blob/5eb833ce413fc3b7c52e1e2790b01c8d584df028/app/src/main/AndroidManifest.xml)

## AWARE

這次最重要的修正：確實有手機定時截圖，但還缺 AI 理解與完整回顧產品。

來源版本：`f77e873baaedfc649fd39ca8f3547f914c3e6b32`

- **平台**：Android（本次核對的 client）。
- **輸入來源**：感測器、app 事件、問卷；可定時截圖。
- **手機自動截圖**：原始碼支援。MediaProjection＋ImageReader 定時擷取，間隔／壓縮可設定，螢幕關閉暫停；有保存與同步接線。未實機驗證。
- **畫面內容理解**：未見核心 AI。查到圖片保存與資料同步；未找到在這條核心路徑上自動理解畫面的視覺模型。
- **AI 摘要**：未見核心 AI。未找到截圖直接產生活動摘要的內建流程。
- **分類方式**：框架／外掛擴充。研究者可加分析或外掛；不能把可擴充當成現成 AI 活動分類。
- **感受紀錄**：經驗採樣問卷。ESM 支援多種問卷題型，可用於感受紀錄。
- **運算位置**：手機收集／儲存／同步；分析需整合。
- **資料位置**：本機圖片／資料庫；研究同步通道。
- **授權**：Apache-2.0。
- **App 使用事件**：原始碼支援。Applications 模組追蹤 app／無障礙事件。
- **網頁資訊**：可截圖／節點資料。可採集資料，但未確認通用瀏覽內容摘要流程。
- **音訊**：依感測器／外掛。框架功能需按實際配置確認，非整套日誌。
- **時間軸**：研究框架。有資料視覺化入口，非 Dayflow 式完成活動日誌。
- **搜尋**：需自行整合。未確認內建畫面內容語意搜尋。

程式碼依據：

- [定時截圖、保存、同步排程](https://github.com/awareframework/aware-client/blob/f77e873baaedfc649fd39ca8f3547f914c3e6b32/aware-core/src/main/java/com/aware/ScreenShot.java)
- [截圖同步服務](https://github.com/awareframework/aware-client/blob/f77e873baaedfc649fd39ca8f3547f914c3e6b32/aware-core/src/main/java/com/aware/syncadapters/ScreenShot_Sync.java)
- [經驗採樣問卷](https://github.com/awareframework/aware-client/blob/f77e873baaedfc649fd39ca8f3547f914c3e6b32/aware-core/src/main/java/com/aware/ESM.java)
- [App 事件模組](https://github.com/awareframework/aware-client/blob/f77e873baaedfc649fd39ca8f3547f914c3e6b32/aware-core/src/main/java/com/aware/Applications.java)

## OpenRecall

和我們同樣有畫面擷取，但主要解決「找回看過的內容」，不是完整活動摘要。

來源版本：`62303e09bfd2491907e788ac5cbbdc21e73bd323`

- **平台**：Windows / macOS / Linux。
- **輸入來源**：定期桌面截圖＋前景 app／標題。
- **手機自動截圖**：僅桌面。沒有已確認的手機擷取端。
- **畫面內容理解**：OCR＋向量。本機 OCR 轉文字，再算文字 embedding；不能等同視覺 LLM 理解活動。
- **AI 摘要**：未見內建。已查核心 app、screenshot、OCR、NLP；主要是保存與檢索，未見每日活動摘要。
- **分類方式**：未見活動分類。語意搜尋不等同工作／娛樂等活動分類。
- **感受紀錄**：未見核心功能。已查核心資料流未見心情紀錄。
- **運算位置**：桌面本機 OCR、embedding 與搜尋。
- **資料位置**：本機截圖與資料庫。
- **授權**：AGPL-3.0。
- **App 使用事件**：桌面 app／標題。隨截圖記錄前景 app 與標題。
- **網頁資訊**：畫面 OCR。可搜尋截圖中的文字。
- **音訊**：未見核心錄音。已查核心模組未見錄音日誌。
- **時間軸**：畫面歷史。可按時間回看截圖。
- **搜尋**：語意搜尋。SentenceTransformer embedding＋相似度。

程式碼依據：

- [定期截圖、去重與入庫](https://github.com/openrecall/openrecall/blob/62303e09bfd2491907e788ac5cbbdc21e73bd323/openrecall/screenshot.py)
- [本機 OCR](https://github.com/openrecall/openrecall/blob/62303e09bfd2491907e788ac5cbbdc21e73bd323/openrecall/ocr.py)
- [文字向量與搜尋](https://github.com/openrecall/openrecall/blob/62303e09bfd2491907e788ac5cbbdc21e73bd323/openrecall/nlp.py)

## Screenpipe

擷取與擴充架構有參考價值；平台與目前授權都不能忽略。

來源版本：`a9742080bdd2970b73c7e2728e1901b8d0d6584f`

- **平台**：Windows / macOS / Linux。
- **輸入來源**：桌面畫面／無障礙樹、音訊、互動事件。
- **手機自動截圖**：僅桌面。本次確認的是桌面擷取，不是 Android 全天記錄。
- **畫面內容理解**：多層處理。有無障礙文字、截圖及受 gate 控制的 OCR；可連後續 AI 工作流。不是每張圖都送視覺模型。
- **AI 摘要**：可接 AI 工作流。透過工作流／agent 等處理擷取資料，具體摘要依啟用功能與服務而異。
- **分類方式**：有工作流分類器。核對到雲端分類器讀取時間、app 名稱與標題；這條路徑並非直接看截圖。
- **感受紀錄**：未確認。未確認標準化每日心情紀錄流程。
- **運算位置**：本機擷取／索引；部分分類與 AI 可在雲端。
- **資料位置**：本機為預設；雲端功能另有資料路徑。
- **授權**：目前為商業／source-available 授權。
- **App 使用事件**：桌面自動。擷取 app 切換與互動事件。
- **網頁資訊**：桌面畫面／文字。無障礙與 OCR 可取得網頁相關內容。
- **音訊**：桌面音訊。可擷取音訊與轉錄；依平台／設定。
- **時間軸**：桌面歷史。可回看和搜尋擷取的歷史。
- **搜尋**：文字／AI 查找。有本機查詢與 AI／agent 整合。

程式碼依據：

- [事件驅動畫面擷取](https://github.com/screenpipe/screenpipe/blob/a9742080bdd2970b73c7e2728e1901b8d0d6584f/crates/screenpipe-engine/src/event_driven_capture.rs)
- [OCR 去重與 gate](https://github.com/screenpipe/screenpipe/blob/a9742080bdd2970b73c7e2728e1901b8d0d6584f/crates/screenpipe-capture/src/ocr_gate.rs)
- [雲端工作流分類輸入](https://github.com/screenpipe/screenpipe/blob/a9742080bdd2970b73c7e2728e1901b8d0d6584f/crates/screenpipe-engine/src/workflow_classifier.rs)
- [目前授權](https://github.com/screenpipe/screenpipe/blob/a9742080bdd2970b73c7e2728e1901b8d0d6584f/LICENSE.md)

## Dayflow

最接近我們要的理解與回顧流程；主要差別仍是桌面平台與手機電量限制。

來源版本：`2c79e1619d7039c476e06e4b85b0246e50bc34fa`

- **平台**：macOS。
- **輸入來源**：定時截圖；另有無障礙資訊。
- **手機自動截圖**：僅桌面。核對的是 Mac 版，沒有已確認的手機擷取端。
- **畫面內容理解**：畫面 AI 分析。例如 Gemini 路徑將截圖合成影片分析，其他 provider 可採不同資料形式。
- **AI 摘要**：活動卡片／回顧。由模型生成活動標題、摘要、詳細描述等。
- **分類方式**：AI 活動分類。活動卡片 prompt／schema 包含 category 與 subcategory。
- **感受紀錄**：有 survey 模組。repo 有自述問卷模組，但本次未深入確認題目與實際發布狀態，不能直接當成完整 wellbeing 流程。
- **運算位置**：Mac 編排；模型可選本機／遠端。
- **資料位置**：Mac 本機保存；遠端分析會傳必要內容。
- **授權**：MIT。
- **App 使用事件**：桌面情境。畫面與無障礙資料輔助理解 app 活動。
- **網頁資訊**：桌面畫面／情境。可由畫面和輔助資料理解瀏覽活動。
- **音訊**：此處未確認。本次重點核對螢幕到活動卡片管線。
- **時間軸**：活動時間軸。活動卡片、分類、每日／每週回顧。
- **搜尋**：日誌問答。可對活動日誌問答。

程式碼依據：

- [定時截圖](https://github.com/JerryZLiu/Dayflow/blob/2c79e1619d7039c476e06e4b85b0246e50bc34fa/Dayflow/Dayflow/Core/Recording/ScreenRecorder.swift)
- [無障礙資訊記錄](https://github.com/JerryZLiu/Dayflow/blob/2c79e1619d7039c476e06e4b85b0246e50bc34fa/Dayflow/Dayflow/Core/Recording/AccessibilityRecorder.swift)
- [Gemini 畫面分析](https://github.com/JerryZLiu/Dayflow/blob/2c79e1619d7039c476e06e4b85b0246e50bc34fa/Dayflow/Dayflow/Core/AI/GeminiDirectProvider+Transcription.swift)
- [AI 摘要與分類欄位](https://github.com/JerryZLiu/Dayflow/blob/2c79e1619d7039c476e06e4b85b0246e50bc34fa/Dayflow/Dayflow/Core/AI/GeminiDirectProvider+ActivityCards.swift)
