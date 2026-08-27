package dev.frostguard.api.chat;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders a transcript as a Discord-style page.
 *
 * <p>The obvious route was to embed one of the existing Discord component libraries, and that was
 * looked at first. They are all npm packages built for web bundlers -- the components ship as ES
 * modules that need Vite or equivalent before a browser can load them. Adding a JavaScript build
 * stage to a Maven desktop application, and shipping the bundle it produces, costs more than the
 * roughly sixty lines of styling it would replace, and it costs it in exactly the currency this
 * feature has to stay cheap in: installed size. So the layout follows their visual spec -- avatar
 * gutter, coloured author line, muted timestamp, quiet mention chips -- written directly.
 *
 * <p>Consecutive messages from the same author are grouped, as Discord does: the avatar and name
 * appear once and the following lines sit under them. Chat is bursty, so ungrouped output is
 * mostly repeated names.
 */
public final class ChatDiscordRenderer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Author colour is derived from the name so a given player keeps one colour across sessions,
     *  the way a role colour behaves, without needing any roster to be stored. */
    private static final String[] AUTHOR_COLOURS = {
            "#5865f2", "#57f287", "#fee75c", "#eb459e", "#ed4245",
            "#3ba55d", "#faa81a", "#00b0f4", "#9b84ec", "#f47fff"
    };

    private ChatDiscordRenderer() {
    }

    public static String render(List<ChatMessage> messages, ZoneId zone) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">").append(styles())
                .append("</head><body><div class=\"log\">");

        if (messages.isEmpty()) {
            sb.append("<p class=\"empty\">No chat captured yet. Enable capture and the transcript "
                    + "fills in on the next pass.</p>");
        }

        String previousAuthor = null;
        String previousChannel = null;
        for (ChatMessage m : messages) {
            String author = m.author().isBlank() ? "unknown" : m.author();
            boolean grouped = author.equals(previousAuthor) && m.channel().equals(previousChannel);
            sb.append(grouped ? groupedLine(m, zone) : leadLine(m, author, zone));
            previousAuthor = author;
            previousChannel = m.channel();
        }

        return sb.append("</div></body></html>").toString();
    }

    private static String leadLine(ChatMessage m, String author, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"msg\"><div class=\"avatar\" style=\"background:")
                .append(colourFor(author)).append("\">").append(escape(initial(author)))
                .append("</div><div class=\"content\"><div class=\"head\">");
        sb.append("<span class=\"author\" style=\"color:").append(colourFor(author)).append("\">")
                .append(escape(author)).append("</span>");
        if (!m.allianceTag().isBlank()) {
            sb.append("<span class=\"tag\">").append(escape(m.allianceTag())).append("</span>");
        }
        sb.append("<span class=\"chan\">").append(escape(m.channel())).append("</span>");
        sb.append("<span class=\"time\">").append(TIME.format(m.capturedAt().atZone(zone)))
                .append("</span></div>").append(bodyHtml(m)).append("</div></div>");
        return sb.toString();
    }

    private static String groupedLine(ChatMessage m, ZoneId zone) {
        return "<div class=\"msg grouped\"><div class=\"avatar spacer\"></div>"
                + "<div class=\"content\">" + bodyHtml(m) + "</div></div>";
    }

    private static String bodyHtml(ChatMessage m) {
        if (m.kind() == ChatMessage.Kind.SYSTEM) {
            return "<div class=\"body system\">" + escape(m.body()) + "</div>";
        }
        if (m.kind() == ChatMessage.Kind.STICKER || m.kind() == ChatMessage.Kind.EMOJI) {
            return "<div class=\"body emoji\">" + escape(m.body()) + "</div>";
        }

        StringBuilder sb = new StringBuilder("<div class=\"body\">")
                .append(withMentions(m.displayBody(), m.mentions()));
        // Keep the original visible under a translation. A reader who speaks the language should
        // not have to trust a machine rendering, and a bad translation is obvious beside its source.
        if (!m.translated().isBlank()) {
            sb.append("<div class=\"orig\">").append(escape(m.body())).append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private static String withMentions(String body, List<String> mentions) {
        String out = escape(body);
        for (String name : mentions) {
            String safe = escape(name);
            out = out.replace("@" + safe, "<span class=\"mention\">@" + safe + "</span>");
        }
        return out;
    }

    private static String colourFor(String author) {
        int h = 0;
        for (int i = 0; i < author.length(); i++) {
            h = h * 31 + author.charAt(i);
        }
        return AUTHOR_COLOURS[Math.floorMod(h, AUTHOR_COLOURS.length)];
    }

    private static String initial(String author) {
        return author.isBlank() ? "?" : author.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String styles() {
        return "<style>"
                + "body{margin:0;background:#313338;color:#dbdee1;"
                + "font-family:'gg sans','Segoe UI',Helvetica,Arial,sans-serif;font-size:15px;}"
                + ".log{padding:16px 12px;}"
                + ".msg{display:flex;gap:12px;padding:2px 8px;}"
                + ".msg:hover{background:#2e3035;}"
                + ".msg.grouped{padding-top:0;}"
                + ".avatar{width:40px;height:40px;border-radius:50%;flex:0 0 40px;color:#fff;"
                + "display:flex;align-items:center;justify-content:center;font-weight:600;}"
                + ".avatar.spacer{background:none;}"
                + ".content{min-width:0;flex:1;}"
                + ".head{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap;}"
                + ".author{font-weight:600;}"
                + ".tag{font-size:11px;background:#4e5058;border-radius:3px;padding:1px 5px;color:#dbdee1;}"
                + ".vip{font-size:11px;background:#faa81a;border-radius:3px;padding:1px 5px;color:#000;font-weight:600;}"
                + ".chan{font-size:11px;color:#949ba4;text-transform:uppercase;letter-spacing:.04em;}"
                + ".time{font-size:12px;color:#949ba4;}"
                + ".body{line-height:1.4;word-wrap:break-word;white-space:pre-wrap;}"
                + ".body.system{color:#949ba4;font-style:italic;}"
                + ".body.emoji{font-size:24px;}"
                + ".orig{margin-top:2px;font-size:13px;color:#949ba4;}"
                + ".mention{background:rgba(88,101,242,.3);color:#c9cdfb;border-radius:3px;padding:0 2px;}"
                + ".empty{color:#949ba4;padding:24px;text-align:center;}"
                + "</style>";
    }
}
