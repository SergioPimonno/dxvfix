package dxvfix.gui;

import dxvfix.i18n.Messages;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Prompts for the requester's own contact email, then opens the default mail client with a
 * pre-filled license-request message addressed to the license administrator -- this only composes
 * the draft; the user still has to review it and hit send themselves (matches how {@code
 * FfmpegInstaller}'s download confirmation and the "Report a bug" menu item both ask before doing
 * anything network/browser-facing).
 * <p>
 * The administrator's address is kept out of plaintext source (base64-decoded at use) rather than
 * written as a literal string, since this repository is public on GitHub -- casual browsing or a
 * scraper wouldn't find a bare {@code user@host} pattern. It's also never displayed anywhere in
 * this dialog itself, only used to build the outgoing message. This is not a security boundary
 * (anyone motivated can decode it, and it necessarily appears in the sent email's "To" field
 * afterwards) -- it just avoids the address sitting in plain text where it'd get indexed/scraped.
 */
final class LicenseRequestDialog {

    private static final String REQUEST_EMAIL_B64 = "OTI2NDY3NjU0MUBtYWlsLnJ1";

    private LicenseRequestDialog() {
    }

    static void show(Component parent, String fingerprint) {
        JTextArea info = new JTextArea(Messages.get("licenseRequest.prompt"));
        info.setEditable(false);
        info.setOpaque(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);

        JTextField emailField = new JTextField();

        JPanel panel = new JPanel(new BorderLayout(6, 8));
        panel.add(info, BorderLayout.NORTH);
        panel.add(emailField, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(380, 100));

        int result = JOptionPane.showConfirmDialog(parent, panel, Messages.get("licenseRequest.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String contactEmail = emailField.getText().trim();
        if (!looksLikeEmail(contactEmail)) {
            JOptionPane.showMessageDialog(parent, Messages.get("licenseRequest.invalidEmail"),
                    Messages.get("licenseRequest.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            URI mailto = buildMailto(contactEmail, fingerprint);
            openMailComposer(mailto);
            JOptionPane.showMessageDialog(parent, Messages.get("licenseRequest.opened"),
                    Messages.get("licenseRequest.title"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, Messages.get("licenseRequest.failed", ex.getMessage()),
                    Messages.get("licenseRequest.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void openMailComposer(URI mailto) throws Exception {
        if (!Desktop.isDesktopSupported()) {
            throw new UnsupportedOperationException("Desktop not supported");
        }
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.MAIL)) {
            desktop.mail(mailto);
        } else if (desktop.isSupported(Desktop.Action.BROWSE)) {
            desktop.browse(mailto);
        } else {
            throw new UnsupportedOperationException("Desktop mail/browse not supported");
        }
    }

    private static boolean looksLikeEmail(String s) {
        return s.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private static URI buildMailto(String contactEmail, String fingerprint) throws Exception {
        String to = new String(Base64.getDecoder().decode(REQUEST_EMAIL_B64), StandardCharsets.UTF_8);
        String subject = Messages.get("licenseRequest.subject");
        String body = Messages.get("licenseRequest.body", fingerprint, contactEmail);
        return new URI("mailto:" + to + "?subject=" + encode(subject) + "&body=" + encode(body));
    }

    /** mailto: URIs use %-encoding for spaces, not application/x-www-form-urlencoded's '+'. */
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
