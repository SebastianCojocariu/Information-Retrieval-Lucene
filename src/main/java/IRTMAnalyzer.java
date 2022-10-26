import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.AttributeSource;
import org.tartarus.snowball.ext.RomanianStemmer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * Custom Analyzer to account for the project's requirements.
 */
public class IRTMAnalyzer extends Analyzer{
    private CharArraySet STOP_WORDS_WITHOUT_DIACRITICS;
    private static HashMap<Character, List<Character>> EXPAND_MAPPINGS_LOWERCASE = new HashMap<>(); // 'a' -> ['a', 'â', 'ă'], etc.
    private static HashMap<Character, Character> DIACRITICS_MAPPINGS = new HashMap<>(); // <--- 'â' -> 'a', 'ă' -> 'a', 'î' -> 'i', etc.
    private final String EXTENDED_STOPWORDS_PATH = "stopwords.txt";

    public IRTMAnalyzer(){
        // Initialize a hashmap storing for each 'special' romanian letter their corresponding set of possible diacritics.
        EXPAND_MAPPINGS_LOWERCASE.put('a', Arrays.asList('a', 'â', 'ă'));
        EXPAND_MAPPINGS_LOWERCASE.put('i', Arrays.asList('i', 'î'));
        EXPAND_MAPPINGS_LOWERCASE.put('s', Arrays.asList('s', 'ş', 'ș'));
        EXPAND_MAPPINGS_LOWERCASE.put('t', Arrays.asList('t', 'ț', 'ţ'));

        // Initialize a hashmap storing for each diacritic the corresponding base letter.
        for (Character key: EXPAND_MAPPINGS_LOWERCASE.keySet()){
            for (Character diacritic: EXPAND_MAPPINGS_LOWERCASE.get(key)){
                DIACRITICS_MAPPINGS.put(diacritic, key);
            }
        }

        // Store romanian stop-words (without diacritics).
        // First, read stop-words from file.
        HashSet<char[]> stopwords_hashset = new HashSet<>();
        try (BufferedReader buffer_reader = new BufferedReader(new FileReader(EXTENDED_STOPWORDS_PATH))) {
            String stop_word;
            while ((stop_word = buffer_reader.readLine()) != null) {
                stopwords_hashset.add(replace_diacritics(stop_word.toCharArray()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Augment stop-words set with Lucene's stop-words set.
        Iterator stopwords_from_package_iterator = new RomanianAnalyzer().getStopwordSet().iterator();
        while (stopwords_from_package_iterator.hasNext()) {
            char[] stop_word = (char[]) stopwords_from_package_iterator.next();
            stopwords_hashset.add(replace_diacritics(stop_word));
        }
        STOP_WORDS_WITHOUT_DIACRITICS = new CharArraySet(stopwords_hashset, true);
    }

    /**
     *
     * @param buffer: buffer that stores a word.
     * @return: The word without diacritics: "mamă" -> "mama".
     */
    public static char[] replace_diacritics(char[] buffer){
        char[] new_word = new char[buffer.length];
        for (int i = 0; i < buffer.length; i++) {
            Character removed_diacritics = DIACRITICS_MAPPINGS.get(buffer[i]);
            if (removed_diacritics == null){
                new_word[i] = buffer[i]; // <--- if there is no special character, just add the letter.
            } else{
                new_word[i] = removed_diacritics; // <--- otherwise, add the letter with removed diacritic.
            }
        }
        return new_word;
    }

    /**
     *
     * @param buffer: buffer that stores a word.
     * @return: A list of words (Strings) containing all the possible diacritics combinations for the given word.
     */
    public static List<String> expand_with_diacritics(char[] buffer){
        char[] buffer_without_diacritics = replace_diacritics(buffer);
        return expand_with_diacritics_helper(buffer_without_diacritics, 0);
    }

    public static List<String> expand_with_diacritics_helper(char[] buffer_without_diacritics, int index) {
        if (index >= buffer_without_diacritics.length){
            return Arrays.asList("");
        }
        else{
            List<Character> possibilities =  EXPAND_MAPPINGS_LOWERCASE.get(buffer_without_diacritics[index]);
            if (possibilities == null){
                possibilities = Arrays.asList(buffer_without_diacritics[index]);
            }
            List<String> suffixes = expand_with_diacritics_helper(buffer_without_diacritics, index + 1);
            List<String> res = new ArrayList<>();
            for (int i = 0; i < possibilities.size(); i++) {
                Character curr_value = possibilities.get(i);
                for (int j = 0; j < suffixes.size(); j++) {
                    String curr_suffix = suffixes.get(j);
                    res.add(curr_value + curr_suffix);
                }
            }
            return res;
        }
    }

    /**
     * Class that implements TokenFilter and replaces diacritics with their base letters.
     */
    private static class IRTMReplaceDiacriticsFilter extends TokenFilter{
        private CharTermAttribute char_term_attribute;

        protected IRTMReplaceDiacriticsFilter(TokenStream input) {
            super(input);
            this.char_term_attribute = addAttribute(CharTermAttribute.class);
        }

        @Override
        public boolean incrementToken() throws IOException {
            if (!input.incrementToken()) {
                return false;
            } else {
                char[] buffer = this.char_term_attribute.buffer(); // <--- get the current processed token.
                char[] converted = replace_diacritics(buffer); // <--- replace all diacritics in the given buffer.
                // Transfer the resulted word into the buffer.
                for(int i = 0; i < buffer.length; i++){
                    buffer[i] = converted[i];
                }
                return true;
            }
        }
    };

    /**
     * Class that implements TokenFilter, creating for each word a relation
     * of synonymy via word -> expand_with_diacritics(word). Internally, the same index for the synonyms is preserved.
     */
    private static class IRTMSynonymFilter extends TokenFilter {
        private final CharTermAttribute char_term_attribute;
        private final PositionIncrementAttribute position_increment_attribute;
        private final Stack<String> synonyms_stack;
        private AttributeSource.State state;
        private final TypeAttribute type_attribute;

        public IRTMSynonymFilter(TokenStream in)
        {
            super(in);
            this.synonyms_stack = new Stack<>();
            this.char_term_attribute = addAttribute(CharTermAttribute.class);
            this.position_increment_attribute = addAttribute(PositionIncrementAttribute.class);
            this.type_attribute = addAttribute(TypeAttribute.class);
        }

        @Override
        public boolean incrementToken() throws IOException
        {
            if (this.synonyms_stack.size() > 0) {
                String synonym = synonyms_stack.pop();
                restoreState(state);
                char_term_attribute.setEmpty().append(synonym);
                position_increment_attribute.setPositionIncrement(0);
                return true;
            } else if (!input.incrementToken()) {
                return false;
            } else {
                final String word = char_term_attribute.toString();
                final int length = char_term_attribute.length();

                List<String> possibilities = expand_with_diacritics(word.toCharArray());
                if (length > 0 && this.synonyms_stack.size() == 0) {
                    this.synonyms_stack.addAll(possibilities);
                    state = captureState();
                }
                return true;
            }
        }
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        StandardTokenizer src = new StandardTokenizer(); // <--- initialize a standard tokenizer object.
        TokenStream result = new LowerCaseFilter(src); // <--- convert each token to lowercase.
        result = new IRTMReplaceDiacriticsFilter(result); // <--- replace all diacritics with their corresponding base letters.
        result = new StopFilter(result, STOP_WORDS_WITHOUT_DIACRITICS); // <--- delete stop-words.
        result = new IRTMSynonymFilter(result); // <--- expand each word to a list of equivalent forms (by trying all possible diacritics).
        result = new SnowballFilter(result, new RomanianStemmer()); // <--- apply the Romanian stemmer for each word.
        result = new IRTMReplaceDiacriticsFilter(result); // <--- remove all diacritics again.
        result = new RemoveDuplicatesTokenFilter(result); // <--- remove duplicates.

        return new TokenStreamComponents(src, result);
    }
}
