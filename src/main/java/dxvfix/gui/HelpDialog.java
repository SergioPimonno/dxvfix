package dxvfix.gui;

import dxvfix.i18n.Messages;
import dxvfix.settings.AppSettings;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reference dialog, split into topics (a left-hand topic list + right-hand content pane, like a
 * classic help viewer) rather than one long scrolling page — the content has grown enough across
 * batch/queue, verification modes, repair strategies, show-watch mode, ffmpeg and licensing that
 * a single page was getting unwieldy.
 * <p>
 * Topic title + body live together per language (as a Java text block) rather than routing the
 * HTML bodies through the {@code .properties} bundles {@link Messages} otherwise uses -- escaping
 * multi-paragraph HTML into property-file syntax is much harder to keep readable/maintainable than
 * one text block per language holding both the title and the content together.
 */
final class HelpDialog {

    private HelpDialog() {
    }

    private static final Map<String, String> TOPICS = loadTopics();

    static void show(Component parent) {
        JList<String> topicList = new JList<>(TOPICS.keySet().toArray(new String[0]));
        topicList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JEditorPane content = new JEditorPane("text/html", "");
        content.setEditable(false);
        content.setPreferredSize(new Dimension(520, 520));

        topicList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            String key = topicList.getSelectedValue();
            if (key == null) return;
            content.setText(wrap(TOPICS.get(key)));
            content.setCaretPosition(0);
        });
        topicList.setSelectedIndex(0);

        JScrollPane listScroll = new JScrollPane(topicList);
        listScroll.setPreferredSize(new Dimension(170, 520));
        JScrollPane contentScroll = new JScrollPane(content);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, contentScroll);
        split.setDividerLocation(170);
        split.setPreferredSize(new Dimension(760, 540));

        JOptionPane.showMessageDialog(parent, split, Messages.get("help.dialogTitle"), JOptionPane.PLAIN_MESSAGE);
    }

    private static String wrap(String body) {
        return "<html><body style='font-family: sans-serif; font-size: 10pt;'>" + body + "</body></html>";
    }

    private static Map<String, String> loadTopics() {
        return switch (AppSettings.getLanguageCode()) {
            case "ru" -> topicsRu();
            case "de" -> topicsDe();
            case "fr" -> topicsFr();
            case "zh" -> topicsZh();
            default -> topicsEn();
        };
    }

    private static Map<String, String> topicsRu() {
        Map<String, String> t = new LinkedHashMap<>();

        t.put("Обзор", """
                <h2>Что делает приложение</h2>
                <p>Ищет и по возможности исправляет битые (повреждённые) кадры в видеофайлах — \
                в первую очередь DXV/DXV3 (кодек Resolume, из-за которого повреждённые кадры вызывают краш Arena), \
                а также Apple ProRes, H.264/AVC и H.265/HEVC.</p>
                <p>Два режима работы:</p>
                <ul>
                <li><b>Пакетная проверка</b> — вручную собранная очередь файлов, проверяются и чинятся по команде.</li>
                <li><b>Сопровождение шоу</b> — постоянный присмотр за папкой с контентом шоу, пока он донабирается.</li>
                </ul>
                <p>См. остальные темы слева.</p>""");

        t.put("Очередь файлов", """
                <h2>Очередь файлов (пакетная проверка)</h2>
                <ul>
                <li>Перетащите файлы или целые папки в окно — из папок рекурсивно подхватываются все .mov/.mp4, \
                включая вложенные подпапки.</li>
                <li>Выделение нескольких файлов — как в проводнике (Ctrl/Shift + клик).</li>
                <li>Крестик справа от строки — убрать один файл из очереди. Кнопка «Удалить выбранные файлы» — \
                убрать все выделенные разом.</li>
                <li>«Сканировать очередь» обрабатывает все файлы со статусом «в очереди» по порядку.</li>
                <li>«Исправить и сохранить как…» — исправляет выбранный файл и сохраняет копию в указанное место.</li>
                </ul>""");

        t.put("Режимы проверки", """
                <h2>Режимы проверки</h2>
                <p><b>Быстрая проверка</b> — разбирает структуру каждого кадра (заголовки, битовый поток DXV, \
                таблицу срезов ProRes, NAL-юниты H.264/H.265) без вызова внешних программ. Быстро, но для H.264/H.265 \
                не проверяет энтропийное кодирование внутри слайса (только целостность framing) — такие кадры \
                помечаются как «поверхностно проверенные».</p>
                <p><b>Углублённая проверка</b> — дополнительно реально декодирует видео через ffmpeg и сверяет, \
                какие кадры декодер фактически смог выдать. Ловит то, что быстрая проверка пропускает, но требует \
                установленный ffmpeg и работает медленнее.</p>""");

        t.put("Способы починки", """
                <h2>Способы починки</h2>
                <p><b>Дублировать соседний кадр</b> — битый кадр заменяется точной копией ближайшего исправного \
                кадра. Работает для любого поддерживаемого кодека и всегда безопасно для внутрикадровых форматов \
                (DXV, ProRes). Для H.264/H.265 в качестве донора по возможности выбирается гарантированно \
                самодостаточный intra/IDR-кадр, чтобы не сломать декодирование соседних кадров.</p>
                <p><b>Сгенерировать кадр (интерполяция)</b> — вместо копии соседа синтезируется новый кадр на основе \
                двух ближайших исправных кадров (motion-compensated interpolation через ffmpeg), затем перекодируется \
                обратно в исходный кодек. Работает только когда: битый кадр — единственный (не серия подряд идущих), \
                у него есть исправные соседи по обе стороны, и кодек поддерживается (ProRes, H.264, H.265, DXV с \
                текстурой DXT1). <b>Не работает для DXV3 с альфа-каналом (DXT5)</b> — у ffmpeg нет энкодера для этого \
                формата. Если генерация недоступна или не удалась — автоматически используется дублирование.</p>""");

        t.put("Сопровождение шоу", """
                <h2>Сопровождение шоу</h2>
                <p>Отдельная вкладка для присмотра за папкой с контентом шоу, пока в неё донабирают/убирают файлы \
                (например, папка <i>Content</i>). В отличие от очереди, не требует вручную добавлять каждый файл — \
                программа сама следит за содержимым папки.</p>

                <h3>Как пользоваться</h3>
                <ol>
                <li>Укажите папку шоу («Обзор…»).</li>
                <li>Выберите режим проверки и способ починки (для сопровождения в реальном времени рекомендуются \
                быстрая проверка и дублирование — см. ниже про нагрузку).</li>
                <li>При необходимости включите флажок <b>«Автоисправление»</b>.</li>
                <li>Нажмите «Начать сопровождение».</li>
                </ol>

                <h3>Что происходит дальше</h3>
                <ul>
                <li>Папка (включая все вложенные подпапки) периодически перепроверяется целиком. Новые и \
                изменившиеся файлы проверяются автоматически; файл, который ещё копируется, распознаётся по \
                меняющемуся размеру и не трогается, пока не «устаканится».</li>
                <li>Найденные битые файлы появляются в таблице снизу: путь, число битых кадров, время обнаружения.</li>
                <li>Если файл убрали из папки шоу — он пропадает и из таблицы.</li>
                <li>Журнал внизу показывает, что программа делает прямо сейчас — все события (обнаружение, починка) \
                также сохраняются в единый файл <code>watch_log.txt</code> внутри подпапки с исправлениями.</li>
                </ul>

                <h3>Автоисправление</h3>
                <p>При включённом флажке каждый найденный битый файл сразу же чинится, а исправленная копия \
                сохраняется в подпапку <code>_DXVFrameDoctor_Fixed</code> внутри той же папки шоу — со своей \
                структурой подпапок, повторяющей исходную (чтобы одноимённые файлы из разных подпапок не \
                перезаписывали друг друга). Сам оригинал не трогается и не удаляется. Эта подпапка с готовыми \
                исправлениями никогда не попадает под повторную проверку — незачем тратить на неё ресурсы.</p>
                <p>При <b>выключенном</b> автоисправлении каждая найденная битая строка в таблице получает свою \
                кнопку «Исправить» — нажатие чинит только этот файл, в отдельном фоновом процессе, не трогая остальные.</p>
                <p>Если сопровождение запускают повторно на той же папке (например, после перезапуска программы), \
                уже готовые исправления на диске не переделываются заново — программа сверяет файлы с их уже \
                исправленными версиями и пропускает то, что чинить не нужно.</p>

                <h3>Остановка</h3>
                <p>«Остановить сопровождение» — отдельная кнопка, не связанная с флажком автоисправления: \
                как только весь контент шоу залит и проверен, сопровождение можно выключить, чтобы программа \
                не расходовала ресурсы сервера впустую. Список найденных проблем при этом остаётся на экране \
                для справки.</p>

                <h3>Нагрузка на систему</h3>
                <p>Режим специально сделан, чтобы не мешать Resolume Arena, работающей на той же машине: файлы \
                обрабатываются строго по одному, не параллельно, фоновый поток запущен с пониженным приоритетом. \
                При быстрой проверке с дублированием нагрузка на процессор практически нулевая (это подтверждено \
                замерами — узкое место там дисковое чтение, а не вычисления). Углублённая проверка и генерация \
                кадров через ffmpeg заметно тяжелее (создают кратковременные всплески нагрузки на несколько секунд \
                на каждый новый битый файл), поэтому для сопровождения в реальном времени лучше оставить быструю \
                проверку с дублированием, а более тщательные режимы использовать точечно, через обычную очередь.</p>""");

        t.put("ffmpeg", """
                <h2>ffmpeg</h2>
                <p>Нужен для углублённой проверки и генерации кадров (в обоих режимах — и в очереди, и в \
                сопровождении шоу). Без ffmpeg доступны только быстрая проверка и дублирование.</p>
                <p>Кнопка «ffmpeg…» — указать существующий ffmpeg.exe вручную. Кнопка «Скачать и установить \
                ffmpeg…» — скачивает официальную сборку для Windows и устанавливает локально, если ffmpeg не \
                найден на компьютере.</p>""");

        t.put("Лицензия", """
                <h2>Лицензия</h2>
                <p>Приложению требуется файл лицензии, привязанный к этому компьютеру (по аппаратному \
                идентификатору устройства). Без действующей лицензии программа не запускается.</p>
                <p>Проверить текущий статус лицензии (кому выдана, до какого числа действует, привязку к этому \
                устройству) — пункт «Проверить лицензию» в меню.</p>
                <p>Лицензии выдаёт администратор отдельным инструментом; если лицензия отсутствует или истекла, \
                обратитесь за новой к тому, кто предоставил вам программу.</p>""");

        t.put("Настройки", """
                <h2>Настройки</h2>
                <p>Пункт «Настройки…» в меню открывает выбор языка интерфейса и цветовой темы.</p>
                <p><b>Тема</b> (светлая/тёмная/системная) применяется сразу же. «Системная» подстраивается под \
                текущий режим Windows (светлый/тёмный) на момент применения.</p>
                <p><b>Язык</b> применяется после перезапуска программы — при смене языка появится соответствующее \
                напоминание.</p>""");

        return t;
    }

    private static Map<String, String> topicsEn() {
        Map<String, String> t = new LinkedHashMap<>();

        t.put("Overview", """
                <h2>What this app does</h2>
                <p>Finds and, where possible, repairs corrupted (broken) frames in video files — primarily \
                DXV/DXV3 (Resolume's codec, where a corrupted frame crashes Arena), as well as Apple ProRes, \
                H.264/AVC and H.265/HEVC.</p>
                <p>Two modes of operation:</p>
                <ul>
                <li><b>Batch check</b> — a manually assembled queue of files, checked and fixed on command.</li>
                <li><b>Show monitoring</b> — continuously watches a show content folder while it's being filled.</li>
                </ul>
                <p>See the other topics on the left.</p>""");

        t.put("File Queue", """
                <h2>File queue (batch check)</h2>
                <ul>
                <li>Drag files or entire folders into the window — folders are walked recursively for every \
                .mov/.mp4, including nested subfolders.</li>
                <li>Multi-select works like a file explorer (Ctrl/Shift + click).</li>
                <li>The "x" on the right of a row removes just that file from the queue. "Remove selected files" \
                removes every selected row at once.</li>
                <li>"Scan queue" processes every file with "queued" status, in order.</li>
                <li>"Fix and save as…" repairs the selected file and saves a copy at the location you choose.</li>
                </ul>""");

        t.put("Verification Modes", """
                <h2>Verification modes</h2>
                <p><b>Fast check</b> — parses each frame's structure (headers, the DXV bitstream, ProRes' slice \
                table, H.264/H.265 NAL units) without calling any external program. Fast, but for H.264/H.265 it \
                doesn't verify the entropy coding inside a slice (only framing integrity) — such frames are marked \
                as "shallow-checked".</p>
                <p><b>Deep check</b> — additionally actually decodes the video through ffmpeg and cross-checks which \
                frames the decoder could actually produce. Catches what the fast check misses, but requires ffmpeg \
                to be installed and runs slower.</p>""");

        t.put("Repair Methods", """
                <h2>Repair methods</h2>
                <p><b>Duplicate the neighboring frame</b> — the broken frame is replaced with an exact copy of the \
                nearest good frame. Works for any supported codec and is always safe for intra-frame formats \
                (DXV, ProRes). For H.264/H.265, the donor is chosen, where possible, to be a guaranteed \
                self-contained intra/IDR frame, so neighboring frames' decoding isn't broken.</p>
                <p><b>Generate a frame (interpolation)</b> — instead of copying a neighbor, a new frame is \
                synthesized from the two nearest good frames (motion-compensated interpolation via ffmpeg), then \
                re-encoded back into the source codec. Only works when: the broken frame is a single one (not a run \
                of consecutive broken frames), it has good neighbors on both sides, and the codec is supported \
                (ProRes, H.264, H.265, DXV with DXT1 texture). <b>Doesn't work for DXV3 with an alpha channel \
                (DXT5)</b> — ffmpeg has no encoder for that format. If generation is unavailable or fails, \
                duplication is used automatically.</p>""");

        t.put("Show Monitoring", """
                <h2>Show monitoring</h2>
                <p>A separate tab for watching a show content folder while files are being added to or removed from \
                it (e.g. a <i>Content</i> folder). Unlike the queue, you don't need to add each file by hand — the \
                app watches the folder's contents itself.</p>

                <h3>How to use it</h3>
                <ol>
                <li>Point it at the show folder ("Browse…").</li>
                <li>Pick a verification mode and repair method (fast check + duplication are recommended for \
                real-time monitoring — see the load notes below).</li>
                <li>Turn on <b>"Auto-fix"</b> if you want it.</li>
                <li>Click "Start monitoring".</li>
                </ol>

                <h3>What happens next</h3>
                <ul>
                <li>The folder (including every nested subfolder) is periodically re-walked in full. New and \
                changed files are checked automatically; a file still being copied in is recognized by its \
                changing size and left alone until it settles.</li>
                <li>Corrupted files found show up in the table below: path, number of bad frames, detection time.</li>
                <li>If a file is removed from the show folder, it also disappears from the table.</li>
                <li>The log at the bottom shows what the app is doing right now — every event (detection, fix) is \
                also saved to one running <code>watch_log.txt</code> file inside the fixes subfolder.</li>
                </ul>

                <h3>Auto-fix</h3>
                <p>With the checkbox on, every corrupted file found is fixed right away, and the repaired copy is \
                saved into a <code>_DXVFrameDoctor_Fixed</code> subfolder inside the same show folder — mirroring \
                the original subfolder structure (so same-named files from different subfolders don't overwrite \
                each other). The original itself is never touched or deleted. This fixes subfolder is never \
                re-scanned — no point spending resources on it.</p>
                <p>With auto-fix <b>off</b>, every corrupted row in the table gets its own "Fix" button — clicking \
                it repairs just that file, in its own background process, without touching the rest.</p>
                <p>If monitoring is started again on the same folder (e.g. after restarting the app), fixes already \
                on disk aren't redone — the app checks files against their already-fixed versions and skips \
                whatever doesn't need fixing.</p>

                <h3>Stopping</h3>
                <p>"Stop monitoring" is a separate button, unrelated to the auto-fix checkbox: once all of the \
                show's content has been loaded in and checked, monitoring can be turned off so the app stops using \
                server resources for nothing. The list of found issues stays on screen for reference.</p>

                <h3>System load</h3>
                <p>This mode is deliberately built not to interfere with Resolume Arena running on the same \
                machine: files are processed strictly one at a time, never in parallel, and the background thread \
                runs at reduced priority. With fast check + duplication, CPU load is practically zero (confirmed by \
                measurement — the bottleneck there is disk reads, not computation). Deep check and frame generation \
                via ffmpeg are noticeably heavier (they create brief load spikes lasting a few seconds for each new \
                corrupted file), so for real-time monitoring it's best to stick with fast check + duplication, and \
                use the more thorough modes selectively, through the regular queue.</p>""");

        t.put("ffmpeg", """
                <h2>ffmpeg</h2>
                <p>Needed for deep checking and frame generation (in both modes — the queue and show monitoring). \
                Without ffmpeg, only fast check and duplication are available.</p>
                <p>The "ffmpeg…" button lets you point at an existing ffmpeg.exe by hand. "Download and install \
                ffmpeg…" downloads the official Windows build and installs it locally if ffmpeg isn't found on the \
                computer.</p>""");

        t.put("License", """
                <h2>License</h2>
                <p>The app requires a license file locked to this computer (by its hardware device identifier). \
                It won't start without a valid license.</p>
                <p>To check the current license status (who it's issued to, its expiry date, whether it matches \
                this device) — use "Check license" in the menu.</p>
                <p>Licenses are issued by an administrator using a separate tool; if a license is missing or \
                expired, contact whoever gave you the app for a new one.</p>""");

        t.put("Settings", """
                <h2>Settings</h2>
                <p>"Settings…" in the menu opens the interface language and color theme choices.</p>
                <p>The <b>theme</b> (light/dark/system) applies immediately. "System" follows Windows' current \
                light/dark mode at the moment it's applied.</p>
                <p>The <b>language</b> applies after restarting the app — you'll see a reminder to that effect \
                when you change it.</p>""");

        return t;
    }

    private static Map<String, String> topicsDe() {
        Map<String, String> t = new LinkedHashMap<>();

        t.put("Übersicht", """
                <h2>Was die Anwendung macht</h2>
                <p>Findet und repariert nach Möglichkeit beschädigte (defekte) Frames in Videodateien — in erster \
                Linie DXV/DXV3 (der Codec von Resolume, bei dem ein beschädigter Frame Arena zum Absturz bringt), \
                sowie Apple ProRes, H.264/AVC und H.265/HEVC.</p>
                <p>Zwei Betriebsarten:</p>
                <ul>
                <li><b>Stapelprüfung</b> — eine manuell zusammengestellte Dateiwarteschlange, die auf Befehl \
                geprüft und repariert wird.</li>
                <li><b>Show-Überwachung</b> — beobachtet fortlaufend einen Ordner mit Show-Inhalten, während \
                dieser befüllt wird.</li>
                </ul>
                <p>Siehe die weiteren Themen links.</p>""");

        t.put("Dateiwarteschlange", """
                <h2>Dateiwarteschlange (Stapelprüfung)</h2>
                <ul>
                <li>Dateien oder ganze Ordner ins Fenster ziehen — aus Ordnern werden rekursiv alle .mov/.mp4 \
                erfasst, auch aus verschachtelten Unterordnern.</li>
                <li>Mehrfachauswahl funktioniert wie im Explorer (Strg/Umschalt + Klick).</li>
                <li>Das "x" rechts in einer Zeile entfernt nur diese Datei aus der Warteschlange. "Ausgewählte \
                Dateien entfernen" entfernt alle markierten Zeilen auf einmal.</li>
                <li>"Warteschlange scannen" verarbeitet alle Dateien mit Status "wartend" der Reihe nach.</li>
                <li>"Reparieren und speichern unter…" repariert die ausgewählte Datei und speichert eine Kopie \
                am gewünschten Ort.</li>
                </ul>""");

        t.put("Prüfmodi", """
                <h2>Prüfmodi</h2>
                <p><b>Schnellprüfung</b> — analysiert die Struktur jedes Frames (Header, den DXV-Bitstream, die \
                ProRes-Slice-Tabelle, H.264/H.265-NAL-Einheiten), ohne ein externes Programm aufzurufen. Schnell, \
                prüft aber bei H.264/H.265 nicht die Entropiecodierung innerhalb eines Slices (nur die Framing-\
                Integrität) — solche Frames werden als "oberflächlich geprüft" markiert.</p>
                <p><b>Tiefenprüfung</b> — dekodiert das Video zusätzlich tatsächlich über ffmpeg und gleicht ab, \
                welche Frames der Decoder wirklich liefern konnte. Erkennt, was die Schnellprüfung übersieht, \
                benötigt aber ein installiertes ffmpeg und läuft langsamer.</p>""");

        t.put("Reparaturmethoden", """
                <h2>Reparaturmethoden</h2>
                <p><b>Nachbar-Frame duplizieren</b> — der defekte Frame wird durch eine exakte Kopie des nächsten \
                intakten Frames ersetzt. Funktioniert für jeden unterstützten Codec und ist bei Intra-Frame-\
                Formaten (DXV, ProRes) immer sicher. Bei H.264/H.265 wird nach Möglichkeit ein garantiert \
                eigenständiger Intra-/IDR-Frame als Quelle gewählt, damit die Dekodierung benachbarter Frames \
                nicht beschädigt wird.</p>
                <p><b>Frame generieren (Interpolation)</b> — statt einer Kopie des Nachbarn wird ein neuer Frame \
                aus den beiden nächsten intakten Frames synthetisiert (bewegungskompensierte Interpolation über \
                ffmpeg) und anschließend zurück in den ursprünglichen Codec kodiert. Funktioniert nur, wenn: der \
                defekte Frame einzeln steht (keine Serie aufeinanderfolgender defekter Frames), er auf beiden \
                Seiten intakte Nachbarn hat und der Codec unterstützt wird (ProRes, H.264, H.265, DXV mit \
                DXT1-Textur). <b>Funktioniert nicht bei DXV3 mit Alphakanal (DXT5)</b> — ffmpeg hat dafür keinen \
                Encoder. Ist die Generierung nicht verfügbar oder schlägt fehl, wird automatisch dupliziert.</p>""");

        t.put("Show-Überwachung", """
                <h2>Show-Überwachung</h2>
                <p>Ein eigener Tab zur Beobachtung eines Ordners mit Show-Inhalten, während Dateien hinzugefügt \
                oder entfernt werden (z. B. ein Ordner <i>Content</i>). Anders als bei der Warteschlange müssen \
                Dateien nicht einzeln von Hand hinzugefügt werden — die Anwendung überwacht den Ordnerinhalt \
                selbst.</p>

                <h3>Verwendung</h3>
                <ol>
                <li>Den Show-Ordner angeben ("Durchsuchen…").</li>
                <li>Prüfmodus und Reparaturmethode wählen (für Echtzeit-Überwachung werden Schnellprüfung und \
                Duplizierung empfohlen — siehe die Hinweise zur Systemlast unten).</li>
                <li>Bei Bedarf das Kästchen <b>"Automatische Reparatur"</b> aktivieren.</li>
                <li>Auf "Überwachung starten" klicken.</li>
                </ol>

                <h3>Was danach passiert</h3>
                <ul>
                <li>Der Ordner (inklusive aller Unterordner) wird periodisch vollständig neu durchsucht. Neue und \
                geänderte Dateien werden automatisch geprüft; eine noch kopierende Datei wird an ihrer sich \
                ändernden Größe erkannt und erst angefasst, wenn sie sich nicht mehr ändert.</li>
                <li>Gefundene defekte Dateien erscheinen in der Tabelle unten: Pfad, Anzahl defekter Frames, \
                Erkennungszeit.</li>
                <li>Wird eine Datei aus dem Show-Ordner entfernt, verschwindet sie auch aus der Tabelle.</li>
                <li>Das Protokoll unten zeigt, was die Anwendung gerade tut — alle Ereignisse (Erkennung, \
                Reparatur) werden zudem in einer einzigen Datei <code>watch_log.txt</code> im Reparatur-Unterordner \
                gespeichert.</li>
                </ul>

                <h3>Automatische Reparatur</h3>
                <p>Ist das Kästchen aktiviert, wird jede gefundene defekte Datei sofort repariert, und die \
                reparierte Kopie wird im Unterordner <code>_DXVFrameDoctor_Fixed</code> innerhalb desselben \
                Show-Ordners gespeichert — mit derselben Unterordnerstruktur wie das Original (damit gleichnamige \
                Dateien aus unterschiedlichen Unterordnern sich nicht gegenseitig überschreiben). Das Original \
                selbst wird nicht angerührt oder gelöscht. Dieser Reparatur-Unterordner wird nie erneut gescannt — \
                dafür lohnen sich keine Ressourcen.</p>
                <p>Ist die automatische Reparatur <b>deaktiviert</b>, erhält jede defekte Zeile in der Tabelle \
                eine eigene Schaltfläche "Reparieren" — ein Klick repariert nur diese Datei, in einem eigenen \
                Hintergrundprozess, ohne die übrigen zu berühren.</p>
                <p>Wird die Überwachung erneut für denselben Ordner gestartet (z. B. nach einem Neustart der \
                Anwendung), werden bereits vorhandene Reparaturen auf der Festplatte nicht wiederholt — die \
                Anwendung gleicht Dateien mit ihren bereits reparierten Versionen ab und überspringt, was nicht \
                erneut repariert werden muss.</p>

                <h3>Beenden</h3>
                <p>"Überwachung stoppen" ist eine eigene Schaltfläche, unabhängig vom Kästchen für automatische \
                Reparatur: Sobald der gesamte Show-Inhalt eingespielt und geprüft ist, kann die Überwachung \
                abgeschaltet werden, damit die Anwendung keine Serverressourcen umsonst verbraucht. Die Liste der \
                gefundenen Probleme bleibt dabei zur Referenz auf dem Bildschirm.</p>

                <h3>Systemlast</h3>
                <p>Dieser Modus ist bewusst so gebaut, dass er Resolume Arena auf derselben Maschine nicht stört: \
                Dateien werden strikt nacheinander verarbeitet, nie parallel, und der Hintergrund-Thread läuft mit \
                reduzierter Priorität. Bei Schnellprüfung mit Duplizierung ist die CPU-Last praktisch null (durch \
                Messungen bestätigt — der Engpass liegt dort beim Festplattenzugriff, nicht bei der Berechnung). \
                Tiefenprüfung und Frame-Generierung über ffmpeg sind spürbar aufwendiger (sie erzeugen kurze \
                Lastspitzen von einigen Sekunden pro neuer defekter Datei); für die Echtzeit-Überwachung sollte \
                daher besser bei Schnellprüfung mit Duplizierung geblieben und die gründlicheren Modi gezielt über \
                die normale Warteschlange genutzt werden.</p>""");

        t.put("ffmpeg", """
                <h2>ffmpeg</h2>
                <p>Wird für die Tiefenprüfung und die Frame-Generierung benötigt (in beiden Modi — Warteschlange \
                und Show-Überwachung). Ohne ffmpeg stehen nur Schnellprüfung und Duplizierung zur Verfügung.</p>
                <p>Die Schaltfläche "ffmpeg…" erlaubt es, eine vorhandene ffmpeg.exe manuell anzugeben. "ffmpeg \
                herunterladen und installieren…" lädt den offiziellen Windows-Build herunter und installiert ihn \
                lokal, falls ffmpeg auf dem Rechner nicht gefunden wird.</p>""");

        t.put("Lizenz", """
                <h2>Lizenz</h2>
                <p>Die Anwendung benötigt eine an diesen Computer gebundene Lizenzdatei (über die Hardware-\
                Kennung des Geräts). Ohne gültige Lizenz startet das Programm nicht.</p>
                <p>Um den aktuellen Lizenzstatus zu prüfen (für wen ausgestellt, bis wann gültig, Bindung an \
                dieses Gerät) — Menüpunkt "Lizenz prüfen".</p>
                <p>Lizenzen werden von einem Administrator mit einem separaten Werkzeug ausgestellt; fehlt die \
                Lizenz oder ist sie abgelaufen, wenden Sie sich an denjenigen, der Ihnen die Anwendung \
                bereitgestellt hat.</p>""");

        t.put("Einstellungen", """
                <h2>Einstellungen</h2>
                <p>"Einstellungen…" im Menü öffnet die Auswahl von Oberflächensprache und Farbschema.</p>
                <p>Das <b>Farbschema</b> (hell/dunkel/systemweit) wird sofort angewendet. "Systemweit" richtet \
                sich nach dem aktuellen Windows-Modus (hell/dunkel) zum Zeitpunkt der Anwendung.</p>
                <p>Die <b>Sprache</b> wird nach einem Neustart der Anwendung wirksam — beim Wechseln erscheint \
                ein entsprechender Hinweis.</p>""");

        return t;
    }

    private static Map<String, String> topicsFr() {
        Map<String, String> t = new LinkedHashMap<>();

        t.put("Aperçu", """
                <h2>Ce que fait l'application</h2>
                <p>Recherche et, si possible, répare les images corrompues dans les fichiers vidéo — \
                principalement en DXV/DXV3 (le codec de Resolume, où une image corrompue fait planter Arena), \
                ainsi qu'en Apple ProRes, H.264/AVC et H.265/HEVC.</p>
                <p>Deux modes de fonctionnement :</p>
                <ul>
                <li><b>Vérification par lots</b> — une file de fichiers assemblée manuellement, vérifiée et \
                réparée sur commande.</li>
                <li><b>Surveillance de show</b> — surveille en continu un dossier de contenu de show pendant \
                qu'il se remplit.</li>
                </ul>
                <p>Voir les autres sujets à gauche.</p>""");

        t.put("File de fichiers", """
                <h2>File de fichiers (vérification par lots)</h2>
                <ul>
                <li>Glissez des fichiers ou des dossiers entiers dans la fenêtre — les dossiers sont parcourus \
                récursivement pour tous les .mov/.mp4, y compris les sous-dossiers imbriqués.</li>
                <li>La sélection multiple fonctionne comme dans l'explorateur (Ctrl/Maj + clic).</li>
                <li>Le « x » à droite d'une ligne retire uniquement ce fichier de la file. « Supprimer les \
                fichiers sélectionnés » retire toutes les lignes sélectionnées d'un coup.</li>
                <li>« Analyser la file » traite tous les fichiers au statut « en attente », dans l'ordre.</li>
                <li>« Réparer et enregistrer sous… » répare le fichier sélectionné et enregistre une copie à \
                l'emplacement choisi.</li>
                </ul>""");

        t.put("Modes de vérification", """
                <h2>Modes de vérification</h2>
                <p><b>Vérification rapide</b> — analyse la structure de chaque image (en-têtes, flux binaire DXV, \
                table des tranches ProRes, unités NAL H.264/H.265) sans appeler de programme externe. Rapide, mais \
                pour H.264/H.265 elle ne vérifie pas le codage entropique à l'intérieur d'une tranche (seulement \
                l'intégrité du framing) — ces images sont marquées comme « vérifiées superficiellement ».</p>
                <p><b>Vérification approfondie</b> — décode en plus réellement la vidéo via ffmpeg et vérifie \
                quelles images le décodeur a effectivement pu produire. Détecte ce que la vérification rapide \
                laisse passer, mais nécessite ffmpeg installé et fonctionne plus lentement.</p>""");

        t.put("Méthodes de réparation", """
                <h2>Méthodes de réparation</h2>
                <p><b>Dupliquer l'image voisine</b> — l'image corrompue est remplacée par une copie exacte de \
                l'image correcte la plus proche. Fonctionne avec tout codec pris en charge et est toujours sûr \
                pour les formats intra-image (DXV, ProRes). Pour H.264/H.265, le donneur choisi, si possible, est \
                une image intra/IDR garantie autonome, afin de ne pas casser le décodage des images voisines.</p>
                <p><b>Générer une image (interpolation)</b> — au lieu de copier une voisine, une nouvelle image \
                est synthétisée à partir des deux images correctes les plus proches (interpolation compensée en \
                mouvement via ffmpeg), puis réencodée dans le codec d'origine. Ne fonctionne que si : l'image \
                corrompue est isolée (pas une série d'images corrompues consécutives), elle a des voisines \
                correctes des deux côtés, et le codec est pris en charge (ProRes, H.264, H.265, DXV avec texture \
                DXT1). <b>Ne fonctionne pas pour DXV3 avec canal alpha (DXT5)</b> — ffmpeg n'a pas d'encodeur pour \
                ce format. Si la génération est indisponible ou échoue, la duplication est utilisée \
                automatiquement.</p>""");

        t.put("Surveillance de show", """
                <h2>Surveillance de show</h2>
                <p>Un onglet séparé pour surveiller un dossier de contenu de show pendant que des fichiers y sont \
                ajoutés ou retirés (par exemple un dossier <i>Content</i>). Contrairement à la file, il n'est pas \
                nécessaire d'ajouter chaque fichier à la main — l'application surveille elle-même le contenu du \
                dossier.</p>

                <h3>Utilisation</h3>
                <ol>
                <li>Indiquez le dossier du show (« Parcourir… »).</li>
                <li>Choisissez un mode de vérification et une méthode de réparation (vérification rapide + \
                duplication sont recommandées pour la surveillance en temps réel — voir les notes de charge \
                ci-dessous).</li>
                <li>Activez la case <b>« Réparation automatique »</b> si besoin.</li>
                <li>Cliquez sur « Démarrer la surveillance ».</li>
                </ol>

                <h3>Ce qui se passe ensuite</h3>
                <ul>
                <li>Le dossier (y compris tous les sous-dossiers imbriqués) est réanalysé périodiquement dans son \
                intégralité. Les fichiers nouveaux ou modifiés sont vérifiés automatiquement ; un fichier encore en \
                cours de copie est reconnu à sa taille qui change et n'est pas touché tant qu'il ne s'est pas \
                stabilisé.</li>
                <li>Les fichiers corrompus trouvés apparaissent dans le tableau ci-dessous : chemin, nombre \
                d'images corrompues, heure de détection.</li>
                <li>Si un fichier est retiré du dossier du show, il disparaît aussi du tableau.</li>
                <li>Le journal en bas affiche ce que fait l'application en ce moment — chaque événement (détection, \
                réparation) est également enregistré dans un unique fichier <code>watch_log.txt</code> à l'intérieur \
                du sous-dossier des réparations.</li>
                </ul>

                <h3>Réparation automatique</h3>
                <p>Case cochée, chaque fichier corrompu trouvé est réparé immédiatement, et la copie réparée est \
                enregistrée dans un sous-dossier <code>_DXVFrameDoctor_Fixed</code> à l'intérieur du même dossier \
                de show — avec la même structure de sous-dossiers que l'original (afin que des fichiers de même nom \
                venant de sous-dossiers différents ne s'écrasent pas mutuellement). L'original lui-même n'est jamais \
                touché ni supprimé. Ce sous-dossier de réparations n'est jamais réanalysé — inutile d'y consacrer \
                des ressources.</p>
                <p>Réparation automatique <b>désactivée</b>, chaque ligne corrompue du tableau reçoit son propre \
                bouton « Réparer » — cliquer dessus ne répare que ce fichier, dans son propre processus en \
                arrière-plan, sans toucher aux autres.</p>
                <p>Si la surveillance est relancée sur le même dossier (par exemple après un redémarrage de \
                l'application), les réparations déjà présentes sur le disque ne sont pas refaites — l'application \
                compare les fichiers à leurs versions déjà réparées et ignore ce qui n'a pas besoin de l'être.</p>

                <h3>Arrêt</h3>
                <p>« Arrêter la surveillance » est un bouton distinct, sans lien avec la case de réparation \
                automatique : une fois tout le contenu du show chargé et vérifié, la surveillance peut être \
                désactivée pour que l'application cesse de consommer des ressources serveur inutilement. La liste \
                des problèmes trouvés reste affichée à titre de référence.</p>

                <h3>Charge système</h3>
                <p>Ce mode est délibérément conçu pour ne pas gêner Resolume Arena tournant sur la même machine : \
                les fichiers sont traités strictement un par un, jamais en parallèle, et le thread d'arrière-plan \
                tourne à priorité réduite. En vérification rapide avec duplication, la charge CPU est pratiquement \
                nulle (confirmé par des mesures — le goulot d'étranglement est la lecture disque, pas le calcul). \
                La vérification approfondie et la génération d'images via ffmpeg sont nettement plus lourdes (elles \
                créent de brefs pics de charge de quelques secondes pour chaque nouveau fichier corrompu) ; pour la \
                surveillance en temps réel, mieux vaut donc rester en vérification rapide + duplication, et \
                utiliser les modes plus poussés ponctuellement, via la file normale.</p>""");

        t.put("ffmpeg", """
                <h2>ffmpeg</h2>
                <p>Nécessaire pour la vérification approfondie et la génération d'images (dans les deux modes — \
                file et surveillance de show). Sans ffmpeg, seules la vérification rapide et la duplication sont \
                disponibles.</p>
                <p>Le bouton « ffmpeg… » permet d'indiquer manuellement un ffmpeg.exe existant. « Télécharger et \
                installer ffmpeg… » télécharge la build Windows officielle et l'installe localement si ffmpeg n'est \
                pas trouvé sur l'ordinateur.</p>""");

        t.put("Licence", """
                <h2>Licence</h2>
                <p>L'application nécessite un fichier de licence lié à cet ordinateur (via l'identifiant matériel \
                de l'appareil). Sans licence valide, le programme ne démarre pas.</p>
                <p>Pour vérifier le statut actuel de la licence (à qui elle est délivrée, jusqu'à quelle date elle \
                est valide, son lien avec cet appareil) — élément de menu « Vérifier la licence ».</p>
                <p>Les licences sont délivrées par un administrateur via un outil séparé ; si une licence est \
                absente ou expirée, contactez la personne qui vous a fourni l'application pour en obtenir une \
                nouvelle.</p>""");

        t.put("Paramètres", """
                <h2>Paramètres</h2>
                <p>« Paramètres… » dans le menu ouvre le choix de la langue de l'interface et du thème de \
                couleurs.</p>
                <p>Le <b>thème</b> (clair/sombre/système) s'applique immédiatement. « Système » suit le mode \
                clair/sombre actuel de Windows au moment de l'application.</p>
                <p>La <b>langue</b> s'applique après le redémarrage de l'application — un rappel s'affiche lors \
                du changement.</p>""");

        return t;
    }

    private static Map<String, String> topicsZh() {
        Map<String, String> t = new LinkedHashMap<>();

        t.put("概览", """
                <h2>本应用的功能</h2>
                <p>查找并尽可能修复视频文件中损坏的帧——主要针对 DXV/DXV3（Resolume 使用的编解码器，其损坏帧会导致 \
                Arena 崩溃），同时也支持 Apple ProRes、H.264/AVC 和 H.265/HEVC。</p>
                <p>两种工作模式：</p>
                <ul>
                <li><b>批量检查</b>——手动收集的文件队列，按命令进行检查和修复。</li>
                <li><b>演出监控</b>——在持续添加内容的演出素材文件夹上进行不间断监控。</li>
                </ul>
                <p>请查看左侧的其他主题。</p>""");

        t.put("文件队列", """
                <h2>文件队列（批量检查）</h2>
                <ul>
                <li>将文件或整个文件夹拖入窗口——文件夹会被递归扫描，包括嵌套的子文件夹中的所有 .mov/.mp4 文件。</li>
                <li>多选操作与资源管理器相同（Ctrl/Shift + 点击）。</li>
                <li>行右侧的“×”仅从队列中移除该文件。“删除所选文件”会一次性移除所有已选中的行。</li>
                <li>“扫描队列”会按顺序处理所有状态为“排队中”的文件。</li>
                <li>“修复并另存为…”会修复所选文件，并将副本保存到指定位置。</li>
                </ul>""");

        t.put("检查模式", """
                <h2>检查模式</h2>
                <p><b>快速检查</b>——解析每一帧的结构（帧头、DXV 比特流、ProRes 切片表、H.264/H.265 的 NAL \
                单元），不调用任何外部程序。速度快，但对于 H.264/H.265 不会校验切片内部的熵编码（仅校验帧封装的完整\
                性）——这类帧会被标记为“浅层检查”。</p>
                <p><b>深度检查</b>——另外通过 ffmpeg 对视频进行真正的解码，并核对解码器实际能够输出哪些帧。能发现快\
                速检查遗漏的问题，但需要安装 ffmpeg，运行速度也更慢。</p>""");

        t.put("修复方式", """
                <h2>修复方式</h2>
                <p><b>复制相邻帧</b>——用最近的完好帧的精确副本替换损坏的帧。适用于任何受支持的编解码器，对帧内编码\
                格式（DXV、ProRes）总是安全的。对于 H.264/H.265，会尽量选择一个确保自包含的 intra/IDR 帧作为来源，\
                以免破坏相邻帧的解码。</p>
                <p><b>生成帧（插值）</b>——不复制相邻帧，而是基于最近的两个完好帧合成一个新帧（通过 ffmpeg 进行运动\
                补偿插值），然后重新编码回原始编解码器。仅在以下情况下有效：损坏帧是孤立的（不是连续多帧损坏），其两\
                侧都有完好的相邻帧，且编解码器受支持（ProRes、H.264、H.265、使用 DXT1 纹理的 DXV）。<b>对带 Alpha \
                通道的 DXV3（DXT5）无效</b>——ffmpeg 没有该格式的编码器。如果生成不可用或失败，会自动改用复制方\
                式。</p>""");

        t.put("演出监控", """
                <h2>演出监控</h2>
                <p>一个独立的标签页，用于在演出素材文件夹（例如 <i>Content</i> 文件夹）不断添加或移除文件的过程中\
                对其进行监控。与队列不同，无需手动逐个添加文件——程序会自行监视该文件夹的内容。</p>

                <h3>使用方法</h3>
                <ol>
                <li>指定演出文件夹（“浏览…”）。</li>
                <li>选择检查模式和修复方式（实时监控建议使用快速检查 + 复制方式——参见下方关于系统负载的说明）。</li>
                <li>如有需要，勾选<b>“自动修复”</b>。</li>
                <li>点击“开始监控”。</li>
                </ol>

                <h3>接下来会发生什么</h3>
                <ul>
                <li>该文件夹（包括所有嵌套子文件夹）会被定期完整重新扫描。新增和已更改的文件会被自动检查；仍在复制\
                中的文件会通过其变化的大小被识别出来，在其大小“稳定”之前不会被处理。</li>
                <li>发现的损坏文件会显示在下方的表格中：路径、损坏帧数、发现时间。</li>
                <li>如果某个文件从演出文件夹中被移除，它也会从表格中消失。</li>
                <li>下方的日志显示程序当前正在做什么——所有事件（发现、修复）也会保存到修复子文件夹内的一个统一文件 \
                <code>watch_log.txt</code> 中。</li>
                </ul>

                <h3>自动修复</h3>
                <p>勾选该选项后，每个发现的损坏文件都会立即被修复，修复后的副本保存在同一演出文件夹内的 \
                <code>_DXVFrameDoctor_Fixed</code> 子文件夹中——并保留与原始结构相同的子文件夹结构（以避免不同子\
                文件夹中同名文件相互覆盖）。原始文件本身不会被修改或删除。这个存放修复结果的子文件夹永远不会被重新\
                扫描——没有必要在它上面浪费资源。</p>
                <p>自动修复<b>关闭</b>时，表格中每一行损坏的文件都会有自己的“修复”按钮——点击它只会在一个独立的后\
                台进程中修复这一个文件，不影响其他文件。</p>
                <p>如果在同一文件夹上再次启动监控（例如程序重启后），磁盘上已有的修复结果不会被重新处理——程序会将\
                文件与其已修复的版本进行比对，跳过不需要再次修复的文件。</p>

                <h3>停止</h3>
                <p>“停止监控”是一个独立的按钮，与自动修复复选框无关：一旦演出的全部内容都已导入并检查完毕，就可以\
                关闭监控，避免程序白白占用服务器资源。已发现问题的列表会保留在屏幕上以供查阅。</p>

                <h3>系统负载</h3>
                <p>该模式经过专门设计，不会干扰同一台机器上运行的 Resolume Arena：文件严格按顺序逐一处理，绝不并\
                行，后台线程以较低优先级运行。在快速检查 + 复制方式下，CPU 占用几乎为零（已通过实测确认——瓶颈在于\
                磁盘读取而非计算）。深度检查以及通过 ffmpeg 进行的帧生成明显更重（每处理一个新发现的损坏文件都会产\
                生持续几秒的短暂负载高峰），因此实时监控最好保持使用快速检查 + 复制方式，更彻底的检查模式则通过常规\
                队列有针对性地使用。</p>""");

        t.put("ffmpeg", """
                <h2>ffmpeg</h2>
                <p>深度检查和帧生成都需要用到它（在队列和演出监控两种模式下均如此）。没有 ffmpeg 时，只能使用快速\
                检查和复制方式。</p>
                <p>“ffmpeg…”按钮可手动指定一个已有的 ffmpeg.exe。“下载并安装 ffmpeg…”会在电脑上未找到 ffmpeg 时\
                下载官方 Windows 版本并在本地安装。</p>""");

        t.put("许可证", """
                <h2>许可证</h2>
                <p>本程序需要一个绑定到本机（通过设备硬件标识符）的许可证文件。没有有效许可证程序无法启动。</p>
                <p>要查看当前许可证状态（颁发对象、有效期至、是否与本设备匹配)——使用菜单中的“检查许可证”。</p>
                <p>许可证由管理员通过单独的工具颁发；如果许可证缺失或已过期，请联系为您提供本程序的人员获取新的许\
                可证。</p>""");

        t.put("设置", """
                <h2>设置</h2>
                <p>菜单中的“设置…”可打开界面语言和配色主题的选择。</p>
                <p><b>主题</b>（浅色/深色/跟随系统）会立即生效。“跟随系统”会在应用时读取当前 Windows 的浅色/深色\
                模式。</p>
                <p><b>语言</b>在重启程序后生效——更改语言时会显示相应的提示。</p>""");

        return t;
    }
}
