package dxvfix.gui;

import dxvfix.i18n.Messages;
import dxvfix.settings.AppSettings;
import dxvfix.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;

/**
 * Interface language + color theme picker, reached from the menu bar. Theme applies immediately
 * (swaps the FlatLaf look and feel live across every open window); language only takes effect on
 * next launch -- see {@link Messages}, which resolves the active bundle once at class-init rather
 * than watching for changes.
 */
final class SettingsDialog {

    private SettingsDialog() {
    }

    static void show(Component parent) {
        JComboBox<AppSettings.Language> languageBox = new JComboBox<>(AppSettings.SUPPORTED_LANGUAGES.toArray(new AppSettings.Language[0]));
        languageBox.setRenderer((list, value, index, isSelected, cellHasFocus) ->
                new JLabel(value == null ? "" : value.nativeName()));
        String currentLangCode = AppSettings.getLanguageCode();
        for (AppSettings.Language lang : AppSettings.SUPPORTED_LANGUAGES) {
            if (lang.code().equals(currentLangCode)) {
                languageBox.setSelectedItem(lang);
                break;
            }
        }

        JRadioButton lightRadio = new JRadioButton(Messages.get("settings.theme.light"));
        JRadioButton darkRadio = new JRadioButton(Messages.get("settings.theme.dark"));
        JRadioButton systemRadio = new JRadioButton(Messages.get("settings.theme.system"));
        ButtonGroup themeGroup = new ButtonGroup();
        themeGroup.add(lightRadio);
        themeGroup.add(darkRadio);
        themeGroup.add(systemRadio);
        switch (AppSettings.getTheme()) {
            case LIGHT -> lightRadio.setSelected(true);
            case DARK -> darkRadio.setSelected(true);
            case SYSTEM -> systemRadio.setSelected(true);
        }

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel langRow = new JPanel(new BorderLayout(6, 6));
        langRow.add(new JLabel(Messages.get("settings.language")), BorderLayout.WEST);
        langRow.add(languageBox, BorderLayout.CENTER);
        langRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(langRow);

        panel.add(Box.createVerticalStrut(12));

        JLabel themeLabel = new JLabel(Messages.get("settings.theme"));
        themeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(themeLabel);
        lightRadio.setAlignmentX(Component.LEFT_ALIGNMENT);
        darkRadio.setAlignmentX(Component.LEFT_ALIGNMENT);
        systemRadio.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(lightRadio);
        panel.add(darkRadio);
        panel.add(systemRadio);

        panel.setPreferredSize(new Dimension(320, panel.getPreferredSize().height));

        int result = JOptionPane.showConfirmDialog(parent, panel, Messages.get("settings.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        AppSettings.Language selectedLanguage = (AppSettings.Language) languageBox.getSelectedItem();
        AppSettings.Theme selectedTheme = lightRadio.isSelected() ? AppSettings.Theme.LIGHT
                : darkRadio.isSelected() ? AppSettings.Theme.DARK : AppSettings.Theme.SYSTEM;

        boolean languageChanged = selectedLanguage != null && !selectedLanguage.code().equals(currentLangCode);
        if (selectedLanguage != null) {
            AppSettings.setLanguageCode(selectedLanguage.code());
        }
        AppSettings.setTheme(selectedTheme);
        ThemeManager.applyLive(selectedTheme);

        if (languageChanged) {
            JOptionPane.showMessageDialog(parent, Messages.get("settings.restartRequired"),
                    Messages.get("settings.title"), JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
