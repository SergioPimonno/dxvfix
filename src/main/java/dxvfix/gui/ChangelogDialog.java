package dxvfix.gui;

import dxvfix.i18n.Messages;
import dxvfix.settings.AppSettings;

import javax.swing.*;
import java.awt.Component;
import java.awt.Dimension;

/**
 * "Changelog" screen: functionality grouped by major version (v1, v2, ...), reached from the menu
 * bar. Content lives here as per-language Java text blocks rather than routed through the
 * {@code .properties} bundles {@link Messages} otherwise uses -- same reasoning as {@link
 * HelpDialog}: escaping multi-paragraph HTML into property-file syntax is harder to keep readable
 * than one text block per language.
 */
final class ChangelogDialog {

    private ChangelogDialog() {
    }

    static void show(Component parent) {
        JEditorPane content = new JEditorPane("text/html", wrap(changelogHtml()));
        content.setEditable(false);
        content.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setPreferredSize(new Dimension(560, 420));
        JOptionPane.showMessageDialog(parent, scroll, Messages.get("mainframe.menu.changelog"), JOptionPane.PLAIN_MESSAGE);
    }

    private static String wrap(String body) {
        return "<html><body style='font-family: sans-serif; font-size: 10pt;'>" + body + "</body></html>";
    }

    private static String changelogHtml() {
        return switch (AppSettings.getLanguageCode()) {
            case "ru" -> RU;
            case "de" -> DE;
            case "fr" -> FR;
            case "zh" -> ZH;
            default -> EN;
        };
    }

    private static final String RU = """
            <h2>История изменений</h2>

            <h3>v1 — Базовая функциональность</h3>
            <ul>
            <li>Обнаружение повреждённых кадров: DXV/DXV3, Apple ProRes, H.264/AVC, H.265/HEVC \
            (быстрая структурная проверка).</li>
            <li>Углублённая проверка через реальное декодирование ffmpeg.</li>
            <li>Два способа починки: дублирование соседнего кадра и генерация кадра через \
            интерполяцию.</li>
            <li>Пакетная очередь файлов: перетаскивание файлов и папок (рекурсивно), множественный \
            выбор, удаление по одному.</li>
            <li>Автоматическое обнаружение и установка ffmpeg в один клик.</li>
            <li>Подписанная лицензия, привязанная к конкретному устройству, с отдельным \
            инструментом для администратора.</li>
            <li>Режим сопровождения шоу: постоянное наблюдение за папкой, автоисправление, единый \
            журнал, сверка с уже исправленными файлами при перезапуске, ручная кнопка починки \
            отдельного файла.</li>
            <li>Встроенная справка по всем режимам работы.</li>
            </ul>

            <h3>v2 — Локализация и удобство использования</h3>
            <ul>
            <li>Выбор языка интерфейса: русский, английский, немецкий, французский, китайский \
            (мандарин).</li>
            <li>Светлая/тёмная/системная цветовая тема.</li>
            <li>Настраиваемый масштаб интерфейса (100–200%) для людей с нарушениями зрения.</li>
            <li>Пункт меню «Сообщить о баге» со ссылкой на GitHub Issues.</li>
            <li>Кнопка «Запросить лицензию»: автоматически формирует запрос по email с указанием \
            кода устройства и контактных данных.</li>
            <li>Политика конфиденциальности, поясняющая обработку email при запросе лицензии.</li>
            <li>Экраны «О программе» и «История изменений».</li>
            </ul>""";

    private static final String EN = """
            <h2>Changelog</h2>

            <h3>v1 — Core functionality</h3>
            <ul>
            <li>Corrupted-frame detection: DXV/DXV3, Apple ProRes, H.264/AVC, H.265/HEVC (fast \
            structural check).</li>
            <li>Deep verification via actual ffmpeg decoding.</li>
            <li>Two repair methods: duplicate the neighboring frame, or generate a frame via \
            interpolation.</li>
            <li>Batch file queue: drag and drop files or folders (recursively), multi-select, \
            remove one at a time.</li>
            <li>One-click ffmpeg detection and installation.</li>
            <li>Signed, machine-locked license file, with a separate tool for the administrator.</li>
            <li>Show monitoring mode: continuous folder watching, auto-fix, a single running log, \
            reconciliation against already-fixed files on restart, a manual per-file fix button.</li>
            <li>Built-in help for every mode of operation.</li>
            </ul>

            <h3>v2 — Localization and usability</h3>
            <ul>
            <li>Interface language selection: Russian, English, German, French, Chinese (Mandarin).</li>
            <li>Light/dark/system color theme.</li>
            <li>Adjustable interface scale (100–200%) for low-vision accessibility.</li>
            <li>"Report a bug" menu item linking to GitHub Issues.</li>
            <li>"Request license" button: automatically composes an email request with the device \
            code and the requester's contact details.</li>
            <li>Privacy policy explaining how the email is handled for license requests.</li>
            <li>About and Changelog screens.</li>
            </ul>""";

    private static final String DE = """
            <h2>Änderungsprotokoll</h2>

            <h3>v1 — Kernfunktionen</h3>
            <ul>
            <li>Erkennung beschädigter Frames: DXV/DXV3, Apple ProRes, H.264/AVC, H.265/HEVC \
            (schnelle strukturelle Prüfung).</li>
            <li>Tiefenprüfung durch tatsächliche Dekodierung über ffmpeg.</li>
            <li>Zwei Reparaturmethoden: Nachbar-Frame duplizieren oder Frame per Interpolation \
            generieren.</li>
            <li>Stapelverarbeitungs-Warteschlange: Dateien oder Ordner per Drag &amp; Drop \
            (rekursiv), Mehrfachauswahl, einzelnes Entfernen.</li>
            <li>Erkennung und Installation von ffmpeg mit einem Klick.</li>
            <li>Signierte, gerätegebundene Lizenzdatei mit separatem Werkzeug für den \
            Administrator.</li>
            <li>Show-Überwachungsmodus: fortlaufende Ordnerüberwachung, automatische Reparatur, ein \
            einziges laufendes Protokoll, Abgleich mit bereits reparierten Dateien nach einem \
            Neustart, manuelle Reparaturschaltfläche pro Datei.</li>
            <li>Integrierte Hilfe für jeden Betriebsmodus.</li>
            </ul>

            <h3>v2 — Lokalisierung und Bedienbarkeit</h3>
            <ul>
            <li>Auswahl der Oberflächensprache: Russisch, Englisch, Deutsch, Französisch, \
            Chinesisch (Mandarin).</li>
            <li>Helles/dunkles/systemweites Farbschema.</li>
            <li>Einstellbare Oberflächenskalierung (100–200 %) für Menschen mit Sehbehinderung.</li>
            <li>Menüpunkt "Fehler melden" mit Verweis auf GitHub Issues.</li>
            <li>Schaltfläche "Lizenz anfordern": erstellt automatisch eine E-Mail-Anfrage mit \
            Gerätecode und Kontaktdaten des Anfragenden.</li>
            <li>Datenschutzhinweis, der die Verwendung der E-Mail-Adresse bei Lizenzanfragen \
            erläutert.</li>
            <li>Bildschirme "Über" und "Änderungsprotokoll".</li>
            </ul>""";

    private static final String FR = """
            <h2>Journal des modifications</h2>

            <h3>v1 — Fonctionnalités de base</h3>
            <ul>
            <li>Détection des images corrompues : DXV/DXV3, Apple ProRes, H.264/AVC, H.265/HEVC \
            (vérification structurelle rapide).</li>
            <li>Vérification approfondie par décodage réel via ffmpeg.</li>
            <li>Deux méthodes de réparation : dupliquer l'image voisine ou générer une image par \
            interpolation.</li>
            <li>File de fichiers par lots : glisser-déposer des fichiers ou dossiers (de façon \
            récursive), sélection multiple, suppression unitaire.</li>
            <li>Détection et installation de ffmpeg en un clic.</li>
            <li>Fichier de licence signé et lié à l'appareil, avec un outil séparé pour \
            l'administrateur.</li>
            <li>Mode de surveillance de show : observation continue d'un dossier, réparation \
            automatique, un journal unique, comparaison avec les fichiers déjà réparés au \
            redémarrage, bouton de réparation manuelle par fichier.</li>
            <li>Aide intégrée pour chaque mode de fonctionnement.</li>
            </ul>

            <h3>v2 — Localisation et ergonomie</h3>
            <ul>
            <li>Choix de la langue de l'interface : russe, anglais, allemand, français, chinois \
            (mandarin).</li>
            <li>Thème de couleurs clair/sombre/système.</li>
            <li>Échelle d'interface réglable (100–200 %) pour l'accessibilité aux personnes \
            malvoyantes.</li>
            <li>Élément de menu « Signaler un bug » renvoyant vers GitHub Issues.</li>
            <li>Bouton « Demander une licence » : compose automatiquement un e-mail de demande \
            avec le code de l'appareil et les coordonnées du demandeur.</li>
            <li>Politique de confidentialité expliquant l'utilisation de l'e-mail lors d'une \
            demande de licence.</li>
            <li>Écrans « À propos » et « Journal des modifications ».</li>
            </ul>""";

    private static final String ZH = """
            <h2>更新日志</h2>

            <h3>v1 — 核心功能</h3>
            <ul>
            <li>损坏帧检测：DXV/DXV3、Apple ProRes、H.264/AVC、H.265/HEVC（快速结构检查）。</li>
            <li>通过 ffmpeg 实际解码进行深度检查。</li>
            <li>两种修复方式：复制相邻帧，或通过插值生成帧。</li>
            <li>批量文件队列：拖放文件或文件夹（递归），支持多选，可逐个移除。</li>
            <li>一键检测并安装 ffmpeg。</li>
            <li>带签名、绑定设备的许可证文件，并提供独立的管理员工具。</li>
            <li>演出监控模式：持续监控文件夹、自动修复、单一运行日志、重启后与已修复文件比对、单文件手动\
            修复按钮。</li>
            <li>针对每种工作模式的内置帮助文档。</li>
            </ul>

            <h3>v2 — 本地化与易用性</h3>
            <ul>
            <li>界面语言选择：俄语、英语、德语、法语、中文（普通话）。</li>
            <li>浅色/深色/跟随系统配色主题。</li>
            <li>可调节的界面缩放（100%–200%），便于视力不佳的用户使用。</li>
            <li>“报告问题”菜单项，链接到 GitHub Issues。</li>
            <li>“申请许可证”按钮：自动生成包含设备代码和申请人联系方式的邮件请求。</li>
            <li>说明申请许可证时邮箱使用方式的隐私政策。</li>
            <li>“关于”与“更新日志”界面。</li>
            </ul>""";
}
