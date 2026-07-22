package schnerry.seymouranalyzer.util;

import java.util.regex.Pattern;

public class StringUtility {
    private static final Pattern FORMATTING_PATTERN = Pattern.compile("§[0-9A-FK-OR]", Pattern.CASE_INSENSITIVE);

    public static boolean isSeymourArmor(String itemName) {
        String cleanName = removeFormatting(itemName);

        return cleanName.contains("Velvet Top Hat") ||
            cleanName.contains("Cashmere Jacket") ||
            cleanName.contains("Satin Trousers") ||
            cleanName.contains("Oxford Shoes");
    }

    public static String removeFormatting(String text) {
        return FORMATTING_PATTERN.matcher(text).replaceAll("");
    }
}