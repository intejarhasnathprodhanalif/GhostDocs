package com.ghostdocs.vault.classifier;

import androidx.annotation.NonNull;

import com.ghostdocs.vault.model.DocumentModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RegexClassifierEngine — Custom Java Regex Intelligence Engine
 *
 * <p>Analyzes raw OCR text, applies compiled regex patterns across polymorphic
 * {@link DocumentRule} implementations, classifies document types, and serializes
 * structured metadata into native {@link JSONObject}.
 *
 * <p><b>OOP Principles:</b>
 * <ul>
 *   <li><b>Polymorphism:</b> Manages a collection of {@link DocumentRule} implementations.
 *       Evaluation is performed uniformly without giant if-else chains.</li>
 *   <li><b>Encapsulation:</b> Compiled regex patterns, matcher loops, and JSON formatting
 *       are shielded from external components.</li>
 * </ul>
 */
public class RegexClassifierEngine {

    // ── Classification Result Container (Encapsulation) ──────────────────────
    public static class ClassificationResult {
        private final String docType;
        private final String title;
        private final List<String> tags;
        private final JSONObject metadataJson;

        public ClassificationResult(@NonNull String docType,
                                    @NonNull String title,
                                    @NonNull List<String> tags,
                                    @NonNull JSONObject metadataJson) {
            this.docType = docType;
            this.title = title;
            this.tags = Collections.unmodifiableList(tags);
            this.metadataJson = metadataJson;
        }

        @NonNull public String getDocType() { return docType; }
        @NonNull public String getTitle() { return title; }
        @NonNull public List<String> getTags() { return tags; }
        @NonNull public JSONObject getMetadataJson() { return metadataJson; }
    }

    private final List<DocumentRule> rules;

    public RegexClassifierEngine() {
        this.rules = new ArrayList<>();
        initializeRules();
    }

    /**
     * Initializes all rule implementations in priority order:
     * 1. Explicit identity cards (NID, Library Card, Student ID, Passport, Driving License)
     * 2. Financial transactions & receipts (ahead of generic ID numbers to prevent account-number collisions)
     * 3. Passwords & credentials
     * 4. Medical prescriptions & reports
     * 5. Generic/fallback identity indicators (labeled ID fields, DOB/Validity, 10/13/17-digit numbers)
     * 6. Study & lecture notes
     */
    private void initializeRules() {
        rules.add(new ExplicitIdentityRule());
        rules.add(new TransactionRule());
        rules.add(new PasswordRule());
        rules.add(new PrescriptionRule());
        rules.add(new IdentityFallbackRule());
        rules.add(new StudyNoteRule());
    }

    /**
     * Classifies raw OCR text, extracts tags, and produces a native JSONObject.
     *
     * @param ocrText Raw text extracted from ML Kit.
     * @param fallbackTitle Default title (e.g. image filename) if no specific title can be inferred.
     * @return Fully populated {@link ClassificationResult}.
     */
    @NonNull
    public ClassificationResult classify(@NonNull String ocrText, @NonNull String fallbackTitle) {
        String matchedType = DocumentModel.TYPE_OTHER;
        List<String> collectedTags = new ArrayList<>();
        String derivedTitle = fallbackTitle;

        // Polymorphic rule evaluation on OCR text
        for (DocumentRule rule : rules) {
            if (rule.matches(ocrText)) {
                matchedType = rule.getDocType();
                collectedTags.addAll(rule.extractTags(ocrText));
                break; // Stop at first strong classification match
            }
        }

        // Fallback: evaluate original filename if OCR yielded no specific classification
        if (DocumentModel.TYPE_OTHER.equals(matchedType) && !fallbackTitle.trim().isEmpty()) {
            String normalizedTitle = fallbackTitle.replace('_', ' ').replace('-', ' ');
            for (DocumentRule rule : rules) {
                if (rule.matches(normalizedTitle)) {
                    matchedType = rule.getDocType();
                    collectedTags.addAll(rule.extractTags(normalizedTitle));
                    break;
                }
            }
        }

        // Generate smart contextual human-friendly title instead of generic screenshot names
        derivedTitle = generateSmartTitle(matchedType, collectedTags, ocrText, fallbackTitle);

        // Build native org.json.JSONObject metadata
        JSONObject jsonMetadata = new JSONObject();
        try {
            jsonMetadata.put("docType", matchedType);
            jsonMetadata.put("title", derivedTitle);
            jsonMetadata.put("snippet", ocrText.length() > 200 ? ocrText.substring(0, 200) : ocrText);

            JSONArray tagsArray = new JSONArray();
            for (String tag : collectedTags) {
                tagsArray.put(tag);
            }
            jsonMetadata.put("tags", tagsArray);
            jsonMetadata.put("tagCount", collectedTags.size());
            jsonMetadata.put("isSensitive", !DocumentModel.TYPE_OTHER.equals(matchedType));

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return new ClassificationResult(matchedType, derivedTitle, collectedTags, jsonMetadata);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Concrete Polymorphic Rules
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Explicit Identity Rule: Matches unambiguous ID cards and official identity documents
     * (National ID, Library Card, Student ID, Employee ID, Passport, Driving License, Admit Card).
     */
    private static class ExplicitIdentityRule implements DocumentRule {
        private static final Pattern EXPLICIT_ID_PATTERN = Pattern.compile(
                "(?i)\\b(national\\s*id|nid|identity\\s*card|id\\s*card|smart\\s*card|" +
                "library\\s*card|student\\s*id|student\\s*card|employee\\s*id|staff\\s*id|" +
                "faculty\\s*id|member\\s*card|membership\\s*card|voter\\s*id|passport|" +
                "driving\\s*licen[cs]e|driver'?s?\\s*licen[cs]e|birth\\s*certificate|" +
                "admit\\s*card|registration\\s*card|hall\\s*ticket)\\b"
        );

        @Override
        public boolean matches(@NonNull String text) {
            return EXPLICIT_ID_PATTERN.matcher(text).find();
        }

        @NonNull
        @Override
        public String getDocType() {
            return DocumentModel.TYPE_NID;
        }

        @NonNull
        @Override
        public List<String> extractTags(@NonNull String text) {
            return extractIdentityTags(text);
        }
    }

    /**
     * Fallback Identity Rule: Matches generic ID card markers (labeled ID fields,
     * Govt/Republic headers, Date of Birth, Validity + Name/Blood/Student, or 10/13/17-digit NID numbers)
     * after financial/medical/password rules have been ruled out.
     */
    private static class IdentityFallbackRule implements DocumentRule {
        private static final Pattern FALLBACK_ID_PATTERN = Pattern.compile(
                "(?i)\\b(govt|republic\\s*of|date\\s*of\\s*birth|d\\.?o\\.?b\\.?|roll\\s*no|reg(?:istration)?\\s*no)\\b"
        );
        private static final Pattern LABELED_ID_FIELD_PATTERN = Pattern.compile(
                "(?i)\\b(?:id|id\\s*no|id\\s*number|card\\s*no)\\s*[:#-]+\\s*[A-Z0-9-]{5,}"
        );
        private static final Pattern CARD_FIELD_COMBINATION_PATTERN = Pattern.compile(
                "(?i)\\b(validity|valid\\s*thru|valid\\s*until|librarian)\\b"
        );
        private static final Pattern NID_NUMBER_PATTERN = Pattern.compile(
                "\\b(\\d{10}|\\d{13}|\\d{17})\\b"
        );

        @Override
        public boolean matches(@NonNull String text) {
            return FALLBACK_ID_PATTERN.matcher(text).find()
                    || LABELED_ID_FIELD_PATTERN.matcher(text).find()
                    || CARD_FIELD_COMBINATION_PATTERN.matcher(text).find()
                    || NID_NUMBER_PATTERN.matcher(text).find();
        }

        @NonNull
        @Override
        public String getDocType() {
            return DocumentModel.TYPE_NID;
        }

        @NonNull
        @Override
        public List<String> extractTags(@NonNull String text) {
            return extractIdentityTags(text);
        }
    }

    /**
     * Shared helper to extract identity tags (card type and ID number) for both explicit
     * and fallback identity rules.
     */
    @NonNull
    private static List<String> extractIdentityTags(@NonNull String text) {
        List<String> tags = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("library card")) {
            tags.add("LIBRARY_CARD");
        } else if (lower.contains("student")) {
            tags.add("STUDENT_ID");
        } else if (lower.contains("passport")) {
            tags.add("PASSPORT");
        } else if (lower.contains("licen")) {
            tags.add("LICENSE");
        } else {
            tags.add("ID");
        }

        Matcher labeledMatcher = Pattern.compile(
                "(?i)\\b(?:id|id\\s*no|card\\s*no|student\\s*id|nid|passport\\s*no|roll\\s*no|reg\\s*no)\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9-]{4,18})\\b"
        ).matcher(text);
        if (labeledMatcher.find()) {
            tags.add("ID:" + labeledMatcher.group(1).toUpperCase(Locale.ROOT));
            return tags;
        }

        Matcher nidMatcher = Pattern.compile("\\b(\\d{10}|\\d{13}|\\d{17})\\b").matcher(text);
        if (nidMatcher.find()) {
            tags.add("ID:" + nidMatcher.group(1));
        }
        return tags;
    }

    /**
     * Transaction & Financial Rule: Matches TrxID, Mobile Banking (bKash/Nagad/Rocket/Upay),
     * transfer confirmation screens, account balances, currency amounts, and Invoices/Receipts.
     */
    private static class TransactionRule implements DocumentRule {
        private static final Pattern TRX_KEYWORD_PATTERN = Pattern.compile(
                "(?i)\\b(trxid|txnid|transaction\\s*id|bkash|nagad|rocket|upay|cellfin|nexuspay|" +
                "pathao\\s*pay|paypal|paytm|gpay|google\\s*pay|apple\\s*pay|zelle|venmo|cash\\s*app|" +
                "stripe|mastercard|visa|dbbl|citytouch|" +
                "send\\s*money|cash\\s*out|cash\\s*in|add\\s*money|mobile\\s*recharge|pay\\s*bill|" +
                "pay\\s*later|request\\s*money|fund\\s*transfer|bank\\s*transfer|wire\\s*transfer|" +
                "money\\s*transfer|priyo\\s*number|my\\s*bkash|my\\s*nagad|" +
                "tap\\s*for\\s*balan|new\\s*balance|available\\s*balance|current\\s*balance|account\\s*balance|" +
                "payment|invoice|bill\\s*to|total\\s*amount|subtotal|grand\\s*total|net\\s*payable|receipt|" +
                "bank\\s*account|savings\\s*account|bank\\s*statement|deposit|withdraw|withdrawal|" +
                "remittance|iban|swift|bdt|taka|usd|eur|gbp|inr)\\b"
        );
        private static final Pattern CURRENCY_AMOUNT_PATTERN = Pattern.compile(
                "(?i)(?:৳|\\$|€|£|₹)\\s*\\d[\\d,]*(?:\\.\\d{1,2})?|" +
                "\\b\\d[\\d,]*(?:\\.\\d{1,2})?\\s*(?:tk|bdt|taka)\\.?\\b|" +
                "\\b(?:tk|bdt)\\.?\\s*\\d[\\d,]*(?:\\.\\d{1,2})?\\b"
        );
        private static final Pattern TRX_ID_VALUE_PATTERN = Pattern.compile(
                "(?i)(?:trxid|txnid|transaction\\s*id)[\\s:#]*([A-Z0-9]{8,14})\\b"
        );

        @Override
        public boolean matches(@NonNull String text) {
            return TRX_KEYWORD_PATTERN.matcher(text).find()
                    || CURRENCY_AMOUNT_PATTERN.matcher(text).find();
        }

        @NonNull
        @Override
        public String getDocType() {
            return DocumentModel.TYPE_RECEIPT;
        }

        @NonNull
        @Override
        public List<String> extractTags(@NonNull String text) {
            List<String> tags = new ArrayList<>();
            tags.add("RECEIPT");

            Matcher matcher = TRX_ID_VALUE_PATTERN.matcher(text);
            if (matcher.find()) {
                tags.add("TrxID:" + matcher.group(1).toUpperCase());
            } else {
                tags.add("TrxRecord");
            }
            return tags;
        }
    }

    /**
     * Password & Credentials Rule: Matches PINs, passwords, recovery phrases, access keys.
     */
    private static class PasswordRule implements DocumentRule {
        private static final Pattern PASSWORD_PATTERN = Pattern.compile(
                "(?i)\\b(password|passwd|pwd|passcode|pin\\s*code|wifi\\s*password|wi-fi\\s*key|" +
                "passphrase|secret\\s*key|private\\s*key|ssh\\s*key|api\\s*key|access\\s*token|" +
                "recovery\\s*phrase|recovery\\s*code|backup\\s*codes|seed\\s*phrase|" +
                "2fa|two-factor|login\\s*credentials|account\\s*credentials)\\b"
        );
        private static final Pattern PASSWORD_VALUE_PATTERN = Pattern.compile(
                "(?i)(?:password|pwd|passcode|pin)[\\s:=]+([\\S]{4,20})\\b"
        );

        @Override
        public boolean matches(@NonNull String text) {
            return PASSWORD_PATTERN.matcher(text).find();
        }

        @NonNull
        @Override
        public String getDocType() {
            return DocumentModel.TYPE_PASSWORD;
        }

        @NonNull
        @Override
        public List<String> extractTags(@NonNull String text) {
            List<String> tags = new ArrayList<>();
            tags.add("PASSWORD");

            Matcher matcher = PASSWORD_VALUE_PATTERN.matcher(text);
            if (matcher.find()) {
                tags.add("SecretMatched");
            }
            return tags;
        }
    }

    /**
     * Prescription Rule: Matches medical prescriptions, dosages, doctors, clinics, lab reports.
     * Note: Avoid matching bare "blood" since ID cards often display "Blood Group: B+".
     */
    private static class PrescriptionRule implements DocumentRule {
        private static final Pattern RX_PATTERN = Pattern.compile(
                "(?i)\\b(rx|dr\\.|doctor|prescription|hospital|clinic|patient|medical|pharmacy|" +
                "medicine|pathology|lab\\s*report|diagnostic|blood\\s*test|blood\\s*report|" +
                "x-?ray|mri|ct\\s*scan|ultrasound|ecg|vaccine|vaccination|physician|mbbs|fcps|" +
                "tablet|capsule|syrup|mg|dosage|diagnos|twice\\s*daily|after\\s*meal|before\\s*meal)\\b|" +
                "(?i)\\b(?:tab|cap|syp|inj)\\."
        );

        @Override
        public boolean matches(@NonNull String text) {
            return RX_PATTERN.matcher(text).find();
        }

        @NonNull
        @Override
        public String getDocType() {
            return DocumentModel.TYPE_PRESCRIPTION;
        }

        @NonNull
        @Override
        public List<String> extractTags(@NonNull String text) {
            List<String> tags = new ArrayList<>();
            tags.add("PRESCRIPTION");
            tags.add("Medical");
            return tags;
        }
    }

    /**
     * Study / Lecture Note Rule: Matches notebook pages, class lectures, university slides,
     * homework assignments, academic diagrams, exercises, definitions.
     */
    private static class StudyNoteRule implements DocumentRule {
        private static final Pattern STUDY_PATTERN = Pattern.compile(
                "(?i)\\b(lecture|homework|assignment|chapter|syllabus|theorem|definition|" +
                "algorithm|formula|equation|exercise|professor|semester|course|class\\s*notes|" +
                "physics|chemistry|biology|calculus|mathematics|discrete|exam|quiz|" +
                "page\\s*\\d+|handout|slide\\s*\\d+|problem\\s*set|solution|faculty|notebook)\\b"
        );

        @Override
        public boolean matches(@NonNull String text) {
            return STUDY_PATTERN.matcher(text).find();
        }

        @NonNull
        @Override
        public String getDocType() {
            return DocumentModel.TYPE_STUDY_NOTE;
        }

        @NonNull
        @Override
        public List<String> extractTags(@NonNull String text) {
            List<String> tags = new ArrayList<>();
            tags.add("STUDY_NOTE");
            tags.add("Lecture");
            return tags;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Smart Contextual Titling Helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Generates a descriptive, human-readable title based on OCR content and classification.
     */
    @NonNull
    public static String generateSmartTitle(@NonNull String matchedType,
                                            @NonNull List<String> collectedTags,
                                            @NonNull String ocrText,
                                            @NonNull String fallbackTitle) {
        // If the user already gave it a meaningful custom name (not Screenshot_... or IMG_...), retain it.
        if (!isGenericFileName(fallbackTitle) && fallbackTitle.trim().length() > 0) {
            return fallbackTitle.trim();
        }

        switch (matchedType) {
            case DocumentModel.TYPE_RECEIPT: {
                String merchant = extractMerchantOrProvider(ocrText);
                String trxId = findTagWithPrefix(collectedTags, "TrxID:");
                String action = extractFinancialAction(ocrText);
                if (merchant != null && trxId != null) {
                    return merchant + " Receipt (" + trxId.replace("TrxID:", "Trx ") + ")";
                } else if (merchant != null && action != null) {
                    return merchant + " " + action;
                } else if (merchant != null) {
                    return merchant + " Receipt";
                } else if (trxId != null) {
                    return "Payment (" + trxId.replace("TrxID:", "Trx ") + ")";
                } else if (action != null) {
                    return action + " Record";
                }
                return "Financial Receipt";
            }
            case DocumentModel.TYPE_NID: {
                String label = resolveIdentityLabel(ocrText, collectedTags);
                String idTag = findTagWithPrefix(collectedTags, "ID:");
                if (idTag != null) {
                    return label + " (" + idTag.replace("ID:", "#") + ")";
                }
                return label;
            }
            case DocumentModel.TYPE_PRESCRIPTION: {
                String doctorOrClinic = extractDoctorOrClinic(ocrText);
                if (doctorOrClinic != null) {
                    return "Prescription - " + doctorOrClinic;
                }
                return "Medical Prescription";
            }
            case DocumentModel.TYPE_PASSWORD: {
                return "Credentials / Secret Note";
            }
            case DocumentModel.TYPE_STUDY_NOTE: {
                String subjectOrTopic = extractStudySubjectOrTopic(ocrText);
                if (subjectOrTopic != null) {
                    return "Lecture Note - " + subjectOrTopic;
                }
                return "Class Lecture / Study Note";
            }
            default: {
                // Try to grab the first clean, meaningful line of text
                String meaningfulLine = extractFirstMeaningfulLine(ocrText);
                if (meaningfulLine != null && !meaningfulLine.isEmpty()) {
                    return meaningfulLine;
                }
                // Fallback to cleaned filename or generic default
                return cleanFileName(fallbackTitle);
            }
        }
    }

    private static boolean isGenericFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return true;
        }
        String clean = name.trim().toLowerCase(Locale.ROOT);
        return clean.matches("^(screenshot|img|pxl|pic|photo|image|doc|scan|wp_|whatsapp|camera|temp|frame|capture|download|file|untitled|imported_|pdf_|camscanner|adobe[ _-]?scan|new[ _-]?doc).*")
                || clean.matches("^[0-9_-]{6,}(\\.[a-z0-9]+)?$");
    }

    private static String cleanFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Secured Document";
        }
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) {
            return name.substring(0, dotIdx);
        }
        return name;
    }

    private static String findTagWithPrefix(List<String> tags, String prefix) {
        if (tags == null) return null;
        for (String tag : tags) {
            if (tag != null && tag.startsWith(prefix)) {
                return tag;
            }
        }
        return null;
    }

    private static String extractMerchantOrProvider(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("bkash") || lower.contains("priyo number") || lower.contains("my bkash")) return "bKash";
        if (lower.contains("nagad")) return "Nagad";
        if (lower.contains("rocket")) return "Rocket";
        if (lower.contains("upay")) return "Upay";
        if (lower.contains("cellfin")) return "CellFin";
        if (lower.contains("nexuspay")) return "NexusPay";
        if (lower.contains("amazon")) return "Amazon";
        if (lower.contains("google play") || lower.contains("google *")) return "Google Play";
        if (lower.contains("apple.com") || lower.contains("itunes") || lower.contains("app store")) return "Apple";
        if (lower.contains("uber")) return "Uber";
        if (lower.contains("foodpanda")) return "Foodpanda";
        if (lower.contains("pathao")) return "Pathao";
        if (lower.contains("daraz")) return "Daraz";
        if (lower.contains("netflix")) return "Netflix";
        if (lower.contains("spotify")) return "Spotify";
        if (lower.contains("paypal")) return "PayPal";
        return null;
    }

    private static String extractFinancialAction(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("send money")) return "Send Money";
        if (lower.contains("cash out")) return "Cash Out";
        if (lower.contains("cash in")) return "Cash In";
        if (lower.contains("add money")) return "Add Money";
        if (lower.contains("mobile recharge")) return "Mobile Recharge";
        if (lower.contains("pay bill")) return "Bill Payment";
        if (lower.contains("tap for balan")) return "Account Dashboard";
        if (lower.contains("new balance") || lower.contains("available balance")) return "Balance Summary";
        return null;
    }

    private static String extractDoctorOrClinic(String text) {
        if (text == null) return null;
        Pattern drPattern = Pattern.compile("(?i)\\b(dr\\.?\\s+[A-Z][a-zA-Z]+(\\s+[A-Z][a-zA-Z]+)?)");
        Matcher drMatcher = drPattern.matcher(text);
        if (drMatcher.find()) {
            return drMatcher.group(1).trim();
        }
        Pattern hospitalPattern = Pattern.compile("(?i)\\b([A-Z][a-zA-Z]+(\\s+[A-Z][a-zA-Z]+)?\\s+(Hospital|Clinic|Medical|Diagnostic))");
        Matcher hospMatcher = hospitalPattern.matcher(text);
        if (hospMatcher.find()) {
            return hospMatcher.group(1).trim();
        }
        return null;
    }

    @NonNull
    private static String resolveIdentityLabel(@NonNull String ocrText,
                                               @NonNull List<String> collectedTags) {
        String lower = ocrText.toLowerCase(Locale.ROOT);
        if (collectedTags.contains("LIBRARY_CARD") || lower.contains("library card") || lower.contains("librarian")) {
            return "Library Card";
        }
        if (lower.contains("admit card") || lower.contains("hall ticket")) {
            return "Admit Card";
        }
        if (collectedTags.contains("PASSPORT") || lower.contains("passport")) {
            return "Passport";
        }
        if (collectedTags.contains("LICENSE") || lower.contains("driving licen") || lower.contains("driver's licen")) {
            return "Driving License";
        }
        if (collectedTags.contains("STUDENT_ID") || lower.contains("student id") || lower.contains("student card")) {
            return "Student ID";
        }
        if (lower.contains("employee id") || lower.contains("staff id") || lower.contains("office id")) {
            return "Employee ID";
        }
        if (lower.contains("national id") || lower.contains("nid") || lower.contains("birth cert")) {
            return "National ID";
        }
        return "Identity Card";
    }

    private static String extractFirstMeaningfulLine(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() < 4) continue;
            if (trimmed.matches("(?i)^(\\d{1,2}:\\d{2}|\\d{1,3}%|lte|4g|5g|volte|am|pm|wifi).*")) continue;
            if (trimmed.matches("^[^a-zA-Z0-9]+$")) continue;

            if (trimmed.length() > 36) {
                return trimmed.substring(0, 36).trim() + "...";
            }
            return trimmed;
        }
        return null;
    }

    /**
     * Checks whether the OCR text indicates academic notes, lectures, or study materials.
     */
    public static boolean isAcademicOrStudyNote(@NonNull String text) {
        if (text == null || text.trim().isEmpty()) return false;
        Pattern pattern = Pattern.compile(
                "(?i)\\b(lecture|homework|assignment|chapter|syllabus|theorem|definition|" +
                "algorithm|formula|equation|exercise|professor|semester|course|class\\s*notes|" +
                "physics|chemistry|biology|calculus|mathematics|discrete|exam|quiz|" +
                "page\\s*\\d+|handout|slide\\s*\\d+|problem\\s*set|solution|faculty|notebook)\\b"
        );
        return pattern.matcher(text).find();
    }

    private static String extractStudySubjectOrTopic(String text) {
        if (text == null) return null;
        Pattern subjectPattern = Pattern.compile(
                "(?i)\\b(Physics|Chemistry|Biology|Calculus|Mathematics|Algorithm|Discrete|Economics|Accounting|English|Computer Science|Statistics)\\b"
        );
        Matcher matcher = subjectPattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}

