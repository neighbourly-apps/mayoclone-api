package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust, aggregator-agnostic parser for IRCTC e-catering order-confirmation
 * emails. It regex-extracts the IRCTC fields (order id, PNR, train no. + name,
 * coach/berth, delivery station code+name, passenger name+phone, delivery date +
 * slot, line items, total, payment mode) from typical order text and degrades
 * gracefully — returning sensible defaults rather than throwing — when a field is
 * absent.
 *
 * <p>The emails arrive as HTML and are flattened to text before parsing, so a
 * label and its value may be separated by spaces, tabs, non-breaking spaces OR a
 * newline (HTML table cells), and a colon is often absent (bare table cells). The
 * patterns here are deliberately liberal about the label→value gap. Table columns
 * are separated by a run of 2+ whitespace or a tab; words inside one cell use a
 * single space — that distinction drives the columnar line-item parser.
 *
 * <p>This handles ALL seeded aggregators so nothing is left unparsed. It is
 * registered last (lowest precedence) so an aggregator-tuned parser such as
 * {@link ZoopEmailParser} can win where it applies. Extraction helpers are
 * {@code protected} so subclasses can refine individual patterns.
 */
@Component
@Order(100)
public class GenericIrctcEmailParser implements IrctcEmailParser {

    /** Any whitespace incl. newline and non-breaking space (label→value gap). */
    private static final String S = "[\\s\\u00a0]";
    /** Horizontal whitespace only (space, tab, nbsp) — stays on one line/cell. */
    private static final String H = "[ \\t\\u00a0]";
    /**
     * Order-id value capture: alphanumeric with INTERNAL hyphens allowed (never leading/
     * trailing), so hyphenated references like {@code RR-889231} are captured whole instead
     * of stopping at the hyphen and yielding just {@code RR}.
     */
    private static final String ID_CAP = "([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)";
    /** A cell boundary: a tab, a run of 2+ spaces/nbsp, or a line break. */
    private static final String CELL_END = "(?=\\t| {2,}|\\u00a0{2,}|\\r|\\n|$)";

    // A date in any of the layouts seen in the wild. parseDate() disambiguates
    // hyphen (dd-MM-yyyy) vs slash (US M/d/yyyy) numeric dates by separator.
    private static final String DATE =
            "\\d{4}-\\d{2}-\\d{2}"                                  // 2026-07-12 (ISO)
            + "|\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}"                     // 12-07-2026, 7/12/2026
            + "|\\d{1,2}[ -][A-Za-z]{3,9}[ -]\\d{4}"               // 12-Jul-2026, 11 Jul 2026
            + "|[A-Za-z]{3,9} \\d{1,2},? ?\\d{4}";                  // Jul 12, 2026 / Jul 12,2026

    // A clock time, optionally a range; seconds are dropped. "21:55", "20:00:00",
    // "13:00-13:30".
    private static final String TIME = "\\d{1,2}:\\d{2}(?:" + H + "*[-–]" + H + "*\\d{1,2}:\\d{2})?";

    // "PNR: 4512367890" / "PNR No    8648384217". A dash / blank / masked
    // "XXXXXXXXXX" simply won't match the 10-digit group, yielding null.
    protected static final Pattern PNR = Pattern.compile(
            "PNR" + S + "*(?:No\\.?|Number)?" + S + "*[:#-]?" + S + "*(\\d{10})",
            Pattern.CASE_INSENSITIVE);

    // Value cell after any "Train …" label, captured up to the next cell boundary.
    // Handles "TRAIN: 22538 / KUSHINAGAR EXP", "Train No./Name  22538 / …",
    // "TRAIN No /NAME  12808 / SAMTA EXPRESS" (spaces around the slash, Yatri Restro),
    // "Train  : Kurj Udz Exp/ 19665", bare "TRAIN No  16032".
    protected static final Pattern TRAIN = Pattern.compile(
            "Train\\b" + H + "*(?:no\\.?" + H + "*/" + H + "*name|no\\.?|number|name)?" + H + "*[:#]?" + H + "*"
                    + "([^\\r\\n\\t]+?)" + CELL_END,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIVE_DIGITS = Pattern.compile("\\d{5}");

    // Combined coach/seat: "RAC/B3/ 47", "B6-32", "S1/54", "B4/43", "B4/ 6",
    // "S1 / 48". A leading class token (RAC/GN/SL) has no digit so it's skipped;
    // coach = letter(s)+digit(s), seat = the trailing number.
    protected static final Pattern COACH_SEAT = Pattern.compile(
            "coac[ht][A-Za-z0-9 /.:\\-]*?([A-Za-z]{1,2}\\d{1,2})" + H + "*[/\\-]" + H + "*(\\d{1,3})",
            Pattern.CASE_INSENSITIVE);
    // Coach only (no seat on the line), e.g. "Coach: B3".
    protected static final Pattern COACH_ONLY = Pattern.compile(
            "coac[ht][A-Za-z0-9 /.:\\-]*?([A-Za-z]{1,2}\\d{1,2})", Pattern.CASE_INSENSITIVE);
    // "Berth: 32" / "Berth No: 32" (fallback seat when not combined with coach).
    protected static final Pattern BERTH = Pattern.compile(
            "Berth" + S + "*(?:No\\.?)?" + S + "*[:#-]?" + S + "*(\\d{1,3})", Pattern.CASE_INSENSITIVE);

    // "Boarding: BCT" / "Boarding Station: BCT"
    protected static final Pattern BOARDING = Pattern.compile(
            "Boarding" + S + "*(?:Station)?" + S + "*(?:Code)?" + S + "*[:#-]?" + S + "*([A-Z]{2,5})\\b",
            Pattern.CASE_INSENSITIVE);

    // Value cell after a station label: "Delivery Station", "Station Name & Code",
    // "Station Code/Name" (Yatri Restro), "Station Name", "Station Code".
    protected static final Pattern STATION = Pattern.compile(
            // \b so "delivery" can't match the tail of "CASH_ON_DELIVERY" and bleed into the
            // next "Station …" cell (which stole the value on Yatri Restro's COD line).
            "(?:\\bdelivery" + S + "*station|\\bstation" + S + "*(?:name" + S + "*&" + S + "*code|code"
                    + S + "*/" + S + "*name|name|code))"
                    + H + "*[:#-]?" + H + "*([^\\r\\n\\t]+?)" + CELL_END,
            Pattern.CASE_INSENSITIVE);

    // Passenger / customer name. The value stops before a 2+ space run, a tab, a
    // newline, a (space-then-)digit, a comma/slash/pipe or a Phone/Contact label —
    // so "Customer: Haidar Ali M. 7355650315" keeps just the name.
    private static final String NAMECAP =
            "([A-Za-z][A-Za-z. ]*?[A-Za-z.])"
                    + "(?=" + H + "{2,}|\\t|\\r|\\n|" + H + "?\\d|,|/|\\||$|Phone|Mobile|Contact|Email)";
    protected static final Pattern CUSTOMER_NAME = Pattern.compile(
            "(?:customer" + S + "*name|passenger" + S + "*name)" + S + "*[:#-]?" + S + "*" + NAMECAP,
            Pattern.CASE_INSENSITIVE);
    protected static final Pattern PASSENGER = Pattern.compile(
            "passenger" + S + "*[:#-]" + S + "*" + NAMECAP, Pattern.CASE_INSENSITIVE);
    // Bare "Customer: X" — but not "Customer Name/Contact/Email/Number/Phone …".
    protected static final Pattern CUSTOMER_BARE = Pattern.compile(
            "customer(?!" + S + "*(?:name|contact|email|number|phone))" + S + "*[:#-]" + S + "*" + NAMECAP,
            Pattern.CASE_INSENSITIVE);

    // Labeled phone: "Phone: 9214362909", "Mobile No.  9978349731",
    // "Customer Contact : 8765962487", "Contact Number  9380818201, …" (first run).
    // Strict 10 consecutive digits so it never bleeds across a column gap.
    protected static final Pattern PHONE = Pattern.compile(
            "(?:phone|mobile|contact|ph)" + S + "*(?:no\\.?|number)?" + S + "*[:#-]?" + S + "*(\\d{10})",
            Pattern.CASE_INSENSITIVE);
    // Labeled phone written with +91/spaces, e.g. "Mobile: +91 98765 43210".
    // Kept on one line (H*) so a following column can't be swallowed.
    protected static final Pattern PHONE_SPACED = Pattern.compile(
            "(?:phone|mobile|contact|ph)" + H + "*(?:no\\.?|number)?" + H + "*[:#-]?" + H + "*(\\+?\\d[\\d -]{8,14})",
            Pattern.CASE_INSENSITIVE);
    // Phone trailing an unlabeled "Customer: <name> <phone>" (e.g. RailRestro).
    protected static final Pattern CUSTOMER_TRAILING_PHONE = Pattern.compile(
            "customer" + S + "*[:#-]" + S + "*[A-Za-z][A-Za-z. ]*?" + S + "+(\\d{10})",
            Pattern.CASE_INSENSITIVE);
    // Phone trailing "Passenger: <name> <phone>" (legacy layout).
    protected static final Pattern PASSENGER_TRAILING_PHONE = Pattern.compile(
            "passenger" + S + "*[:#-]" + S + "*[A-Za-z][A-Za-z. ]*?" + S + "+(\\d{10})",
            Pattern.CASE_INSENSITIVE);

    // amountToCollect: "Payment to collect 0", "BALANCE TO PAY ₹ 0",
    // "(Amount to collect)  Rs. 252".
    protected static final Pattern TO_COLLECT = Pattern.compile(
            "(?:amount" + S + "*to" + S + "*collect|payment" + S + "*to" + S + "*collect"
                    + "|balance" + S + "*to" + S + "*pay)[^\\r\\n\\d]*?([0-9][0-9,]*(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    // Qualified grand total (preferred over a bare "Total"/sub total). Skips a
    // parenthetical between the label and the number ("Grand Total (Inclusive …) ₹ 200").
    protected static final Pattern GRAND_TOTAL = Pattern.compile(
            "(?:grand" + S + "*total|order" + S + "*total|payable" + S + "*total"
                    + "|total" + S + "*payable|amount" + S + "*payable|net" + S + "*payable"
                    + "|payable" + S + "*amount)[^\\r\\n\\d]*?([0-9][0-9,]*(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE);
    // A plain "Total" at a line/cell start (so "Sub Total"/"Subtotal" never win).
    protected static final Pattern TOTAL_PLAIN = Pattern.compile(
            "(?m)(?:^|\\n|\\t| {2,})" + H + "*(?:grand" + H + "+)?total\\b"
                    + "[^\\r\\n\\d]*?([0-9][0-9,]*(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE);
    // Any bare currency value (last-ditch amount fallback).
    protected static final Pattern AMOUNT = Pattern.compile(
            "(?:₹|Rs\\.?|INR)" + S + "*([0-9][0-9,]*(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE);

    // Payment-mode signals.
    private static final Pattern COD_HINT = Pattern.compile(
            "cash[ _]?on[ _]?delivery|\\bCOD\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREPAID_HINT = Pattern.compile("prepaid", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAID_HINT = Pattern.compile(
            "payment" + S + "*mode" + S + "*[:#-]?" + S + "*paid|\\bPAID\\b", Pattern.CASE_INSENSITIVE);

    // Legacy inline line items: "2 x Veg Biryani - ₹180" (qty-first) and
    // "Veg Biryani x 2 - ₹180" (name-first). Used only when the columnar table
    // parser finds nothing.
    protected static final Pattern LINE_ITEM = Pattern.compile(
            "(\\d+)\\s*[xX*×]\\s*(.+?)\\s*[-–:@=]?\\s*(?:₹|Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.\\d{1,2})?)");
    protected static final Pattern LINE_ITEM_ALT = Pattern.compile(
            "([A-Za-z][A-Za-z0-9 .()&'-]*?)\\s*[xX×]\\s*(\\d+)\\s*[-–:@=]?\\s*"
                    + "(?:₹|Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.\\d{1,2})?)");

    // A table header row that opens the line-item section, e.g.
    // "Item  Price  Quantity  Amount" or "SL#  Item  Description  Qty  Price  GST  Amount".
    private static final Pattern ITEM_HEADER = Pattern.compile(
            "\\bitem\\b.*\\b(?:price|qty|quantity|amount|total)\\b", Pattern.CASE_INSENSITIVE);
    // A whole table cell that is purely a number (optionally ₹/Rs/INR or an "xN" qty).
    private static final Pattern NUMERIC_CELL = Pattern.compile(
            "^(?:₹|Rs\\.?|INR|x)?" + H + "*([0-9][0-9,]*(?:\\.\\d{1,2})?)$", Pattern.CASE_INSENSITIVE);
    // A numeric cell that carries an explicit currency symbol (₹/Rs/INR) — the
    // strongest "this is a money row" signal, and marks a self-contained item row.
    private static final Pattern CURRENCY_CELL = Pattern.compile(
            "^(?:₹|Rs\\.?|INR)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL_SPLIT = Pattern.compile("\\t+| {2,}|\\u00a0{2,}");

    private static final DateTimeFormatter[] TEXT_DATE_FORMATS = {
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH)
    };

    @Override
    public boolean supports(Aggregator agg, String from, String subject) {
        // The generic parser handles any routed aggregator, so nothing is unparsed.
        return agg != null;
    }

    @Override
    public ParsedOrder parse(Aggregator agg, String from, String subject, String body, String messageId) {
        String safeBody = body == null ? "" : body;
        String fallbackId = agg.getCode() + "-" + Math.abs(String.valueOf(messageId).hashCode());

        String[] train = extractTrain(safeBody);
        String[] station = extractStation(safeBody);
        BigDecimal toCollect = extractToCollect(safeBody);
        String paymentMode = detectPaymentMode(safeBody, toCollect);

        return new ParsedOrder(
                agg.getCode(),
                extractOrderId(safeBody, fallbackId),
                extractGroup(PNR, safeBody, null),
                train[0],
                train[1],
                extractCoach(safeBody),
                extractSeat(safeBody),
                extractGroup(BOARDING, safeBody, null),
                station[0],
                station[1],
                extractPassenger(safeBody),
                extractPhone(safeBody),
                extractDeliveryDate(safeBody),
                extractSlot(safeBody),
                extractAmount(safeBody),
                "INR",
                "CONFIRMED",
                extractItems(safeBody),
                subject,
                messageId,
                paymentMode,
                toCollect,
                extractSubtotal(safeBody),
                extractGst(safeBody),
                extractDeliveryFee(safeBody),
                extractDiscount(safeBody),
                extractCharges(safeBody)
        );
    }

    // ---- extra charges ------------------------------------------------------

    // An explicit additive fee line, e.g. "(+) Gateway Platform Fees   ₹ 30". The label is
    // captured up to the amount; only the "(+)" additive form is matched so item/summary
    // numbers can't be mistaken for a fee.
    private static final Pattern PLUS_FEE = Pattern.compile(
            "\\(\\+\\)" + H + "*([A-Za-z][A-Za-z .&/()-]*?)" + H + "*[:#-]?" + H
                    + "*(?:₹|Rs\\.?|INR)?" + H + "*([0-9][0-9,]*(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);

    // Labels already carried by their own field (subtotal/GST/delivery/discount, totals) —
    // matched anywhere in the label so "GST on Delivery Charge" (already summed into GST) or
    // "Delivery Charge" are NOT double-counted as an extra charge.
    private static final Pattern CATEGORISED_CHARGE = Pattern.compile(
            "gst|delivery|discount|sub" + S + "*total|base" + S + "*price|order" + S + "*total"
                    + "|grand" + S + "*total|paid|balance|net\\b|to" + S + "*collect",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extra named bill lines the aggregator added on top of subtotal/GST/delivery/discount —
     * e.g. "Gateway Platform Fees". Default: every "(+) label ₹amount" line whose label isn't
     * already one of the categorised fields. Aggregator parsers may override for other formats.
     */
    protected List<com.mayoclone.domain.OrderCharge> extractCharges(String body) {
        List<com.mayoclone.domain.OrderCharge> charges = new ArrayList<>();
        Matcher m = PLUS_FEE.matcher(body);
        while (m.find()) {
            String label = m.group(1).replaceAll("\\s+", " ").trim();
            if (label.isEmpty() || CATEGORISED_CHARGE.matcher(label).find()) {
                continue;
            }
            BigDecimal amt = parseMoney(m.group(2));
            if (amt != null && amt.signum() > 0) {
                charges.add(new com.mayoclone.domain.OrderCharge(label, amt));
            }
        }
        return charges;
    }

    // ---- bill breakdown -----------------------------------------------------

    /** Sub Total / Subtotal / Base Price Total line; null when absent. */
    protected BigDecimal extractSubtotal(String body) {
        return labelMoney(body, "sub\\s*total|base\\s*price\\s*total");
    }

    /** GST line; null when absent. */
    protected BigDecimal extractGst(String body) {
        return labelMoney(body, "gst");
    }

    /** Delivery Charge / Delivery Fee line; null when absent. */
    protected BigDecimal extractDeliveryFee(String body) {
        return labelMoney(body, "delivery\\s*(?:charge|fee)");
    }

    /** Discount line; null when the label is absent OR carries no number (e.g. "Discount* ₹"). */
    protected BigDecimal extractDiscount(String body) {
        return labelMoney(body, "discount");
    }

    // ---- order id -----------------------------------------------------------

    protected String extractOrderId(String body, String fallback) {
        // Prefer the strongest signal first, falling back to weaker ones.
        String v = firstGroup(body, "IRCTC" + S + "*Order" + S + "*No\\.?" + S + "*[:#-]?" + S + "*([A-Za-z0-9]+)");
        if (v != null) return v;
        v = firstGroup(body, "Invoice" + S + "+([A-Za-z0-9]+)");
        if (v != null) return v;
        v = firstGroup(body, "Order" + S + "*Number" + S + "*[:#-]?" + S + "*" + ID_CAP);
        if (v != null) return v;
        v = firstGroup(body, "Order" + S + "*(?:id|no\\.?)" + S + "*[:#-]?" + S + "*" + ID_CAP);
        if (v != null) return v;
        v = firstGroup(body, "Order" + S + "*#" + S + "*[:#-]?" + S + "*" + ID_CAP);
        if (v != null) return v;
        v = firstGroup(body, "#([A-Za-z0-9]{3,})");
        return v != null ? v : fallback;
    }

    // ---- train --------------------------------------------------------------

    /** @return {trainNumber, trainName}; either may be null. */
    protected String[] extractTrain(String body) {
        String[] horizontal = new String[]{null, null};
        Matcher m = TRAIN.matcher(body);
        if (m.find()) {
            horizontal = splitTrain(m.group(1).trim());
        }
        // A real train number inline wins immediately.
        if (horizontal[0] != null) {
            return horizontal;
        }
        // Vertical layout: "TRAIN No" on one line, "12190" (or "12612 / MAS GARIB RATH")
        // on the next. The horizontal TRAIN regex can't cross the newline (and worse,
        // it captures the bare "No" label as a spurious name), so recover here.
        for (String cand : labelValues(body, "train\\s*no\\.?(?:/name)?|train\\s*number|train\\s*name|train")) {
            String[] t = splitTrain(cand);
            if (t[0] != null || t[1] != null) {
                return t;
            }
        }
        return horizontal;
    }

    /** Split a train cell like "22538 / KUSHINAGAR EXP" into {number, name}. */
    private static String[] splitTrain(String seg) {
        seg = seg.trim();
        Matcher num = FIVE_DIGITS.matcher(seg);
        String number = num.find() ? num.group() : null;
        String name = seg.replaceFirst("\\d{5}", "").replaceAll("[/:]", " ").replaceAll("\\s+", " ").trim();
        return new String[]{number, name.isBlank() ? null : name};
    }

    // ---- coach / seat -------------------------------------------------------

    protected String extractCoach(String body) {
        Matcher m = COACH_SEAT.matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        Matcher c = COACH_ONLY.matcher(body);
        if (c.find()) {
            return c.group(1);
        }
        return verticalCoachSeat(body)[0];
    }

    protected String extractSeat(String body) {
        Matcher m = COACH_SEAT.matcher(body);
        if (m.find()) {
            return m.group(2);
        }
        String seat = verticalCoachSeat(body)[1];
        if (seat != null) {
            return seat;
        }
        return extractGroup(BERTH, body, null);
    }

    // Combined coach/seat when it sits on its OWN value line ("B5/ 64", "M1/74",
    // "G4/65"), reached from the "COACH NO / SEAT NO" (etc.) label on the line above.
    private static final Pattern COACH_SEAT_VALUE = Pattern.compile(
            "(?:[A-Za-z]{2,3}[/\\-])?" + H + "*([A-Za-z]{1,2}\\d{1,2})" + H + "*[/\\-]" + H + "*(\\d{1,3})");

    /** @return {coach, seat} parsed from the vertical value line; either may be null. */
    private String[] verticalCoachSeat(String body) {
        for (String cand : labelValues(body,
                "coach\\s*no\\.?\\s*/\\s*seat\\s*no\\.?|coach\\s*/?\\s*seat|coach\\s*/?\\s*berth|coach")) {
            Matcher m = COACH_SEAT_VALUE.matcher(cand);
            if (m.find()) {
                return new String[]{m.group(1), m.group(2)};
            }
        }
        return new String[]{null, null};
    }

    // ---- station ------------------------------------------------------------

    /** @return {deliveryStationCode, deliveryStationName}; either may be null. */
    protected String[] extractStation(String body) {
        Matcher m = STATION.matcher(body);
        if (m.find()) {
            String v = m.group(1).trim();
            // Guard: in the vertical layout the label's trailing space is all that
            // precedes the newline, so the horizontal regex can capture a blank cell.
            if (!v.isEmpty()) {
                return parseStation(v);
            }
        }
        // Vertical layout: "DELIVERY STATION" then the value on the next line.
        for (String cand : labelValues(body, "delivery\\s*station|station\\s*name(?:\\s*&?\\s*code)?|station")) {
            if (!cand.isBlank()) {
                return parseStation(cand.trim());
            }
        }
        return new String[]{null, null};
    }

    /**
     * Split a station cell into {code, name}. A token is treated as a CODE only
     * when it is unambiguous: (a) a parenthetical {@code NAME (VGLJ)}; (b) a slash
     * form {@code CODE / NAME} or {@code NAME / CODE} where one side is a 2–5 char
     * all-caps code; (c) a leading all-caps code followed by a mixed-case proper
     * name ({@code NDLS New Delhi}); or (d) a bare single all-caps token of 3–5
     * letters ({@code VGLJ}, {@code NDLS}). A multi-word value with no such code
     * is treated wholly as the name — so {@code NEW DELHI} does NOT lose "NEW" to a
     * bogus code.
     */
    protected String[] parseStation(String seg) {
        // (a) NAME (CODE)
        Matcher paren = Pattern.compile("^(.+?)\\s*\\(([A-Za-z]{2,5})\\)\\s*$").matcher(seg);
        if (paren.matches()) {
            return new String[]{paren.group(2).toUpperCase(Locale.ROOT), paren.group(1).trim()};
        }
        // (b) CODE / NAME  or  NAME / CODE
        int slash = seg.indexOf('/');
        if (slash >= 0) {
            String left = seg.substring(0, slash).trim();
            String right = seg.substring(slash + 1).trim();
            if (left.matches("[A-Z]{2,5}")) {
                return new String[]{left, right.isBlank() ? null : right};
            }
            if (right.matches("[A-Z]{2,5}")) {
                return new String[]{right, left.isBlank() ? null : left};
            }
            return new String[]{null, seg};
        }
        // (c) CODE Name — leading all-caps code, then a MIXED-CASE proper name.
        // Requiring a lowercase letter in the name half prevents an all-caps
        // two-word NAME ("NEW DELHI") from being split into code+name.
        Matcher cn = Pattern.compile("^([A-Z]{2,5})\\s+(\\S.*)$").matcher(seg);
        if (cn.matches() && cn.group(2).chars().anyMatch(Character::isLowerCase)) {
            return new String[]{cn.group(1), cn.group(2).trim()};
        }
        // (d) bare CODE — a single all-caps 3–5 letter token with no spaces.
        if (seg.matches("[A-Z]{3,5}")) {
            return new String[]{seg, null};
        }
        // otherwise the whole value is the name
        return new String[]{null, seg};
    }

    // ---- passenger / phone --------------------------------------------------

    protected String extractPassenger(String body) {
        Matcher m = CUSTOMER_NAME.matcher(body);
        if (m.find()) return m.group(1).trim();
        Matcher p = PASSENGER.matcher(body);
        if (p.find()) return p.group(1).trim();
        Matcher c = CUSTOMER_BARE.matcher(body);
        if (c.find()) return c.group(1).trim();
        return "Unknown";
    }

    protected String extractPhone(String body) {
        Matcher m = PHONE.matcher(body);
        if (m.find()) return normalizePhone(m.group(1));
        Matcher s = PHONE_SPACED.matcher(body);
        if (s.find()) return normalizePhone(s.group(1));
        Matcher c = CUSTOMER_TRAILING_PHONE.matcher(body);
        if (c.find()) return normalizePhone(c.group(1));
        Matcher p = PASSENGER_TRAILING_PHONE.matcher(body);
        return p.find() ? normalizePhone(p.group(1)) : null;
    }

    private static String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() >= 10) {
            return digits.substring(digits.length() - 10);
        }
        return digits.isEmpty() ? null : digits;
    }

    // ---- dates & slot -------------------------------------------------------

    protected LocalDate extractDeliveryDate(String body) {
        // Journey date wins (F1/F4 differ from ETA); then Delivery Date, then the
        // date embedded in Delivery Time, then any "Delivery …" line, then any date.
        // Each inline (same-line) lookup has a vertical (next-line) sibling.
        LocalDate d = findDate(body, "journey" + S + "*date");
        if (d == null) d = dateFromLabel(body, "journey\\s*date");
        if (d == null) d = findDate(body, "delivery" + S + "*date");
        if (d == null) d = dateFromLabel(body, "delivery\\s*date");
        if (d == null) d = findDate(body, "delivery" + S + "*time");
        if (d == null) d = dateFromLabel(body, "delivery\\s*time(?:\\s*\\(eta\\))?");
        if (d == null) d = findDate(body, "delivery");
        if (d == null) d = findDate(body, "");
        return d;
    }

    private LocalDate findDate(String body, String label) {
        Pattern p = Pattern.compile(label + "[^\\r\\n]*?(" + DATE + ")", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(body);
        if (m.find()) {
            LocalDate d = parseDate(m.group(1).trim());
            if (d != null) return d;
        }
        return null;
    }

    /** Vertical date: first {@link #labelValues} candidate that carries a parseable date. */
    private LocalDate dateFromLabel(String body, String label) {
        Pattern dp = Pattern.compile("(" + DATE + ")");
        for (String cand : labelValues(body, label)) {
            Matcher m = dp.matcher(cand);
            if (m.find()) {
                LocalDate d = parseDate(m.group(1).trim());
                if (d != null) return d;
            }
        }
        return null;
    }

    protected String extractSlot(String body) {
        String t = findTime(body, "delivery" + S + "*time");
        if (t == null) t = timeFromLabel(body, "delivery\\s*time(?:\\s*\\(eta\\))?");
        if (t == null) t = findTime(body, "delivery" + S + "*date");
        if (t == null) t = timeFromLabel(body, "delivery\\s*date");
        if (t == null) t = findTime(body, "\\bETA\\b");
        if (t == null) t = timeFromLabel(body, "eta");
        if (t == null) t = findTime(body, "delivery");
        return t;
    }

    private String findTime(String body, String label) {
        Pattern p = Pattern.compile(label + "[^\\r\\n]*?(" + TIME + ")(?::\\d{2})?", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1).replaceAll("\\s", "") : null;
    }

    /** Vertical time: first {@link #labelValues} candidate that carries a clock time. */
    private String timeFromLabel(String body, String label) {
        Pattern tp = Pattern.compile("(" + TIME + ")(?::\\d{2})?");
        for (String cand : labelValues(body, label)) {
            Matcher m = tp.matcher(cand);
            if (m.find()) {
                return m.group(1).replaceAll("\\s", "");
            }
        }
        return null;
    }

    /** Multi-format date parse; disambiguates numeric dates by their separator. */
    protected LocalDate parseDate(String raw) {
        try {
            if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(raw);
            }
            if (raw.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
                // Indian dd/MM/yyyy preferred (every partner is an Indian service). Fall
                // back to US M/d/yyyy only when dd/MM is impossible (first field > 12), so
                // "09/07/2026" is 9 Jul (not 7 Sep) and "07/13/2026" still reads as 13 Jul.
                try {
                    return LocalDate.parse(raw, DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH));
                } catch (RuntimeException indianFormatFailed) {
                    return LocalDate.parse(raw, DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ENGLISH));
                }
            }
            if (raw.matches("\\d{1,2}-\\d{1,2}-\\d{4}")) { // Indian hyphen: dd-MM-yyyy
                return LocalDate.parse(raw, DateTimeFormatter.ofPattern("d-M-yyyy", Locale.ENGLISH));
            }
        } catch (RuntimeException ignored) {
            // fall through to textual month formats
        }
        String norm = raw.replace(",", " ").replace("-", " ").replaceAll("\\s+", " ").trim();
        for (DateTimeFormatter fmt : TEXT_DATE_FORMATS) {
            try {
                return LocalDate.parse(norm, fmt);
            } catch (RuntimeException ignored) {
                // try the next format
            }
        }
        return null;
    }

    // ---- amount & amount-to-collect ----------------------------------------

    protected BigDecimal extractAmount(String body) {
        BigDecimal grand = lastGroupMoney(GRAND_TOTAL, body);
        if (grand != null) return grand;
        BigDecimal plain = lastGroupMoney(TOTAL_PLAIN, body);
        if (plain != null) return plain;
        // Vertical layout: a strong total label ("Grand Total", "Order Total",
        // "Paid Total", …) on one line and its ₹ value on the next. Bare "Total"
        // is deliberately excluded (RailRestro labels its GST-inclusive grand
        // "Subtotal" and its food total "Total").
        BigDecimal vgrand = labelMoney(body,
                "grand\\s*total|order\\s*total|paid\\s*total|payable\\s*total|net\\s*payable|amount\\s*payable");
        if (vgrand != null) return vgrand;
        // Fallback: the largest bare currency value in the body.
        Matcher m = AMOUNT.matcher(body);
        BigDecimal best = null;
        while (m.find()) {
            BigDecimal val = parseMoney(m.group(1));
            if (best == null || val.compareTo(best) > 0) best = val;
        }
        return best != null ? best : BigDecimal.ZERO;
    }

    protected BigDecimal extractToCollect(String body) {
        Matcher m = TO_COLLECT.matcher(body);
        if (m.find()) {
            return parseMoney(m.group(1));
        }
        // Vertical: "Payment to collect" / "(Amount to collect)" then "0" (or "Rs. 0/-") next line.
        return labelMoney(body, "payment\\s*to\\s*collect|amount\\s*to\\s*collect|balance\\s*to\\s*pay");
    }

    // ---- payment mode -------------------------------------------------------

    /** "COD" | "PREPAID" | "PAID" | null. */
    protected String detectPaymentMode(String body, BigDecimal toCollect) {
        if (COD_HINT.matcher(body).find()) return "COD";
        if (PREPAID_HINT.matcher(body).find()) return "PREPAID";
        if (PAID_HINT.matcher(body).find()) return "PAID";
        if (toCollect != null && toCollect.compareTo(BigDecimal.ZERO) > 0) return "COD";
        return null;
    }

    // ---- line items ---------------------------------------------------------

    protected List<OrderItem> extractItems(String body) {
        // Vertical layout first: each item's name, ₹price, qty and ₹amount are on
        // their OWN lines. Its signature (a currency-only line) never occurs in the
        // horizontal/tab tables, so this returns empty for those and we fall through.
        List<OrderItem> vertical = extractVerticalItems(body);
        if (!vertical.isEmpty()) {
            return vertical;
        }
        List<OrderItem> items = extractColumnarItems(body);
        if (!items.isEmpty()) {
            return items;
        }
        // Fallback: legacy inline layouts ("2 x Veg Biryani - ₹180").
        Matcher m = LINE_ITEM.matcher(body);
        while (m.find()) {
            items.add(new OrderItem(m.group(2).trim(), Integer.parseInt(m.group(1)), parseMoney(m.group(3))));
        }
        if (items.isEmpty()) {
            Matcher a = LINE_ITEM_ALT.matcher(body);
            while (a.find()) {
                items.add(new OrderItem(a.group(1).trim(), Integer.parseInt(a.group(2)), parseMoney(a.group(3))));
            }
        }
        return items;
    }

    // A money-only value line: a currency-prefixed number OR a bare decimal. A bare
    // integer is deliberately NOT money (train no. / qty would false-match).
    private static final Pattern MONEY_ONLY = Pattern.compile(
            "^(?:₹|Rs\\.?|INR)" + H + "*[0-9][0-9,]*(?:\\.[0-9]{1,2})?$"
                    + "|^[0-9][0-9,]*\\.[0-9]{1,2}$", Pattern.CASE_INSENSITIVE);
    // A quantity-only line: an integer, optionally "xN".
    private static final Pattern INT_ONLY = Pattern.compile("^x?" + H + "*([0-9]{1,4})$", Pattern.CASE_INSENSITIVE);
    // A line that can start an item name: it contains a letter.
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

    /**
     * Parse items laid out one value per line — the shape real HTML-rendered
     * aggregator emails arrive in:
     * <pre>
     *   Veg Thali
     *   ₹ 114.32     ← money-only  (unit price)
     *   1            ← qty         (optional)
     *   ₹ 114.32     ← money-only  (line amount, consumed)
     * </pre>
     * An item starts on a name line (has a letter, not a summary/header label)
     * IMMEDIATELY followed by a money-only line. Summary rows (Sub Total, GST, …)
     * and their value lines are skipped, so nothing past the item block is captured.
     * Returns empty for horizontal/tab tables (their price cells never sit alone on
     * a line), so the columnar parser stays in charge of those.
     */
    private List<OrderItem> extractVerticalItems(String body) {
        List<OrderItem> items = new ArrayList<>();
        List<String> lines = nonEmptyLines(body);
        int i = 0;
        int n = lines.size();
        while (i < n) {
            String line = lines.get(i);
            // Skip anything that can't be a name: summary labels, money-only,
            // qty-only, or letterless noise ("-", "₹", "-->").
            if (isSummaryLine(line) || MONEY_ONLY.matcher(line).matches()
                    || INT_ONLY.matcher(line).matches() || !HAS_LETTER.matcher(line).find()) {
                i++;
                continue;
            }
            // A name line only becomes an item when the very next line is its price.
            if (i + 1 < n && MONEY_ONLY.matcher(lines.get(i + 1)).matches()) {
                // firstMoney (not parseMoney) so the dot in "Rs." can't leak into the value.
                BigDecimal price = firstMoney(lines.get(i + 1));
                int j = i + 2;
                int qty = 1;
                if (j < n) {
                    Matcher q = INT_ONLY.matcher(lines.get(j));
                    if (q.matches()) {
                        qty = Integer.parseInt(q.group(1));
                        j++;
                    }
                }
                if (j < n && MONEY_ONLY.matcher(lines.get(j)).matches()) {
                    j++; // consume the line-amount cell
                }
                items.add(new OrderItem(line, Math.max(qty, 1), price));
                i = j;
            } else {
                i++;
            }
        }
        return items;
    }

    /**
     * Parse the tabular line-item section every real aggregator uses, robust to
     * value variation (any qty, multi-word names) and layout variation:
     * <ul>
     *   <li>{@code Name  ₹price  qty  ₹amount} — name and numbers on one row;</li>
     *   <li>{@code ₹price  xNqty  ₹amount} with the name (and an optional
     *       description) on the preceding line(s);</li>
     *   <li>a {@code Name} row then a {@code Description  price  qty  amount} row.</li>
     * </ul>
     * A row is treated as an item when it carries a money cell (₹/Rs/INR or a
     * decimal) OR — after an "Item Price Qty Amount" header — any numeric cells, so
     * currency-less tables and header-less tables both parse. This means the header
     * is helpful but not required, and stray/decoy lines and bare-digit field rows
     * (phone, train no.) are ignored. Summary rows (Sub Total, GST, Grand Total, …)
     * are skipped.
     */
    private List<OrderItem> extractColumnarItems(String body) {
        List<OrderItem> items = new ArrayList<>();
        boolean inItems = false;
        String pendingName = null;
        for (String line : body.split("\\r?\\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (ITEM_HEADER.matcher(trimmed).find()) {
                inItems = true;
                pendingName = null;
                continue;
            }
            if (isSummaryLine(trimmed)) {
                pendingName = null;
                continue;
            }

            List<BigDecimal> numbers = new ArrayList<>();
            List<Boolean> isInteger = new ArrayList<>();
            // Price/qty/amount columns only ever follow the item name; a numeric cell
            // BEFORE the name is a row index ("SL#") and must not be mistaken for the
            // quantity. We track which numbers came after the first text cell.
            List<BigDecimal> afterName = new ArrayList<>();
            List<Boolean> afterNameInt = new ArrayList<>();
            boolean hasCurrency = false;
            String firstText = null;
            for (String cell : CELL_SPLIT.split(trimmed)) {
                String c = cell.strip();
                if (c.isEmpty()) {
                    continue;
                }
                Matcher n = NUMERIC_CELL.matcher(c);
                if (n.matches()) {
                    BigDecimal v = parseMoney(n.group(1));
                    boolean intCell = !c.contains(".");
                    numbers.add(v);
                    isInteger.add(intCell);
                    if (firstText != null) {
                        afterName.add(v);
                        afterNameInt.add(intCell);
                    }
                    if (CURRENCY_CELL.matcher(c).find()) {
                        hasCurrency = true;
                    }
                } else if (firstText == null) {
                    firstText = c;
                }
            }

            boolean hasMoney = hasCurrency || isInteger.contains(Boolean.FALSE); // ₹/Rs or a decimal
            boolean itemRow = hasMoney || (inItems && !numbers.isEmpty());

            if (!itemRow) {
                // Not an item: a pure-text line becomes the pending item name (the
                // first such line; later text lines are its description); a bare-digit
                // field row (phone/train no.) clears any stale pending name.
                if (numbers.isEmpty()) {
                    if (pendingName == null && firstText != null) {
                        pendingName = firstText;
                    }
                } else {
                    pendingName = null;
                }
                continue;
            }

            // Name resolution. When the row carries its own text cell that is the
            // NAME (self-contained "Name ₹price qty ₹amount" row) use it; when the
            // row's text is a DESCRIPTION accompanying a preceding name (no currency
            // symbol on the row, a pending name is set) keep the pending name; when
            // the row has no text at all ("₹price xqty ₹amount") use the pending name.
            String name;
            if (firstText != null) {
                name = (pendingName != null && !hasCurrency) ? pendingName : firstText;
            } else {
                name = pendingName;
            }
            if (name == null) {
                pendingName = null;
                continue; // numbers with no discernible name — skip defensively
            }

            // Use only the columns that follow the name (dropping any SL# index);
            // fall back to all numbers when the name has none after it.
            List<BigDecimal> relevant = afterName.isEmpty() ? numbers : afterName;
            List<Boolean> relevantInt = afterName.isEmpty() ? isInteger : afterNameInt;
            int qty = smallestInteger(relevant, relevantInt);
            BigDecimal amount = relevant.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal unit = qty > 0
                    ? amount.divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP)
                    : amount;
            items.add(new OrderItem(name, Math.max(qty, 1), unit));
            pendingName = null;
        }
        return items;
    }

    /** Smallest integer-valued cell = the quantity; defaults to 1. */
    private static int smallestInteger(List<BigDecimal> numbers, List<Boolean> isInteger) {
        int qty = Integer.MAX_VALUE;
        for (int i = 0; i < numbers.size(); i++) {
            if (isInteger.get(i)) {
                qty = Math.min(qty, numbers.get(i).intValue());
            }
        }
        return qty == Integer.MAX_VALUE ? 1 : qty;
    }

    // NOTE: deliberately does NOT include a bare "extra" — IRCTC add-on items are commonly
    // named "Extra Gravy"/"Extra Roti"/"Extra Curd", and matching the substring dropped them
    // from the KOT while the grand total still counted them. No real bill-summary row in any
    // supported aggregator is labelled just "Extra".
    private static final Pattern SUMMARY = Pattern.compile(
            "total|gst|discount|delivery|payable|balance|cashback" + "|paid|base" + S + "*price"
                    + "|subtotal|\\bnet\\b|to" + S + "*collect|charge",
            Pattern.CASE_INSENSITIVE);

    private static boolean isSummaryLine(String line) {
        return line.startsWith("(") || SUMMARY.matcher(line).find();
    }

    // ---- small helpers ------------------------------------------------------

    /** Generic single-group extraction; trims and returns {@code fallback} when absent. */
    protected String extractGroup(Pattern pattern, String body, String fallback) {
        Matcher m = pattern.matcher(body);
        if (m.find()) {
            String v = m.group(1);
            return v == null ? fallback : v.trim();
        }
        return fallback;
    }

    private static String firstGroup(String body, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(body);
        return m.find() ? m.group(1).trim() : null;
    }

    private BigDecimal lastGroupMoney(Pattern pattern, String body) {
        Matcher m = pattern.matcher(body);
        BigDecimal last = null;
        while (m.find()) {
            last = parseMoney(m.group(1));
        }
        return last;
    }

    protected BigDecimal parseMoney(String raw) {
        // Strip a currency token (Rs./Rs/₹/INR) and thousands separators, then take the
        // numeric value. The old blind replaceAll("[^0-9.]","") turned "Rs. 159" into
        // ".159" (=0.16) and threw on "Rs. 159.00" (two dots) — a whole order dumped to
        // the review queue. Anchoring on the number is robust to any currency prefix.
        String cleaned = raw.replaceAll("(?i)rs\\.?|inr|₹", " ").replace(",", "");
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(cleaned);
        if (!m.find()) {
            throw new NumberFormatException("no numeric value in '" + raw + "'");
        }
        return new BigDecimal(m.group());
    }

    // ---- vertical (one-value-per-line) layout helpers -----------------------

    /** Body split into trimmed, non-empty lines (the vertical layout's cells). */
    private static List<String> nonEmptyLines(String body) {
        List<String> out = new ArrayList<>();
        for (String l : body.split("\\r?\\n")) {
            String s = l.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // Leading noise before a value cell: separators, currency-less punctuation.
    private static final Pattern LEADING_SEP = Pattern.compile("^[\\s:#*.\\-/&;()]+");
    // First money token in a string: an optional ₹/Rs/INR then a number.
    private static final Pattern FIRST_MONEY = Pattern.compile(
            "(?:₹|Rs\\.?|INR)?" + H + "*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);

    /**
     * Candidate value(s) for a label, covering both layouts: for {@code "Sub Total  ₹ 171.42"}
     * the value is inline after the label; for a vertical {@code "Sub Total\n₹ 171.42"} it is
     * the next line. Returns the inline remainder (when non-empty) and the next non-empty line,
     * so a caller can pick whichever carries the datum it wants. Empty when the label is absent.
     * The label must match at the START of a line (so packed horizontal rows like
     * "ORDER No  x  PNR No  y" don't misfire — those are handled by the field regexes).
     */
    protected List<String> labelValues(String body, String labelRegex) {
        Pattern p = Pattern.compile("(?i)^[\\s(]*(?:" + labelRegex + ")\\b(.*)$");
        List<String> lines = nonEmptyLines(body);
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = p.matcher(lines.get(i));
            if (m.matches()) {
                List<String> out = new ArrayList<>(2);
                String inline = LEADING_SEP.matcher(m.group(1)).replaceFirst("").strip();
                if (!inline.isEmpty()) {
                    out.add(inline);
                }
                if (i + 1 < lines.size()) {
                    out.add(lines.get(i + 1).strip());
                }
                return out;
            }
        }
        return List.of();
    }

    /** First {@link #labelValues} candidate that carries a money value; null when none does. */
    protected BigDecimal labelMoney(String body, String labelRegex) {
        for (String cand : labelValues(body, labelRegex)) {
            BigDecimal v = firstMoney(cand);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Parse the first ₹/Rs/INR-or-bare number in {@code s}; null when there is none. */
    protected BigDecimal firstMoney(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = FIRST_MONEY.matcher(s);
        return m.find() ? parseMoney(m.group(1)) : null;
    }
}
