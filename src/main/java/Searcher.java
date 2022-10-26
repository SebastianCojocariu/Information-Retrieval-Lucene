import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Searcher class that searches, based on a query, a set of indexed files (as processed and saved by the Indexer).
 */
public class Searcher {

  private Searcher() {}

  public static void main(String[] args) throws Exception {
    String usage = " [-help HELP] [-index_path INDEX_PATH] [-query_string QUERY_STRING] [-max_hits MAX_HITS] [-interactive INTERACTIVE]\n\n" +
                  "Apply the QUERY_STRING over the indexed files located at INDEX_PATH. It only returns the most important MAX_HITS (or less). " +
                  "For interactive querying, set INTERACTIVE to true (default is false)" + "\n" +
                  "Default INDEX_PATH='./indexed_files/', QUERY_STRING='' and MAX_HITS=25";

    Path index_path = Paths.get("indexed_files/");
    String field = "contents";
    int max_hits = 25; // <--- maximum number of hits.
    boolean interactive = false;
    String query_string = "";

    for(int i = 0; i < args.length; i++) {
      if ("-index_path".equals(args[i])) {
        index_path = Paths.get(args[i + 1]);
        i++;
      } else if ("-query_string".equals(args[i])) {
        query_string = args[i + 1].trim();
        i++;
      } else if ("-max_hits".equals(args[i])) {
        max_hits = Integer.parseInt(args[i + 1]);
        i++;
      } else if ("-interactive".equals(args[i])) {
        interactive = Boolean.parseBoolean(args[i + 1]);
        i++;
      } else if ("-help".equals(args[i])) {
        System.out.println(usage);
        System.exit(0);
      }
    }

    IndexReader reader = DirectoryReader.open(FSDirectory.open(index_path));
    IndexSearcher searcher = new IndexSearcher(reader);
    Analyzer analyzer = new IRTMAnalyzer();
    QueryParser parser = new QueryParser(field, analyzer);

    // Non-interactive case
    if (!interactive) {
      if (max_hits <= 0 || query_string.length() == 0){
        System.out.println("MAX_HITS must be positive and query_string must be non-empty! Received MAX_HITS=" + max_hits + ", query_string='" + query_string + "'");
        System.out.println(usage);
        System.exit(-1);
      }
      Query query = parser.parse(query_string);
      //System.out.println("#### Converted query: " + query.toString(field));

      TopDocs results = searcher.search(query, max_hits);
      ScoreDoc[] hits = results.scoreDocs;

      System.out.println("\n#### Total number of documents matched: " + results.totalHits.value + ". They are (sorted by score):\n");
      for (int i = 0; i < results.totalHits.value; i++) {
        String full_path = searcher.doc(hits[i].doc).get("full_path");
        System.out.println("[" + (i + 1) + "]" + ": " + full_path);
      }
      System.out.println();
    }
    // Interactive case
    else {
      BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      while (true) {
        System.out.println("#### Pass a custom query or write 'exit' to close the program!");
        String line = input.readLine().trim();

        if (line == null || line.length() == 0){
          continue;
        }

        if (line.equalsIgnoreCase("exit")) {
          System.out.println("#### Search complete ####");
          break;
        }

        Query query = parser.parse(line);
        System.out.println("#### Converted query: " + query.toString(field));

        TopDocs results = searcher.search(query, max_hits);
        ScoreDoc[] hits = results.scoreDocs;

        System.out.println("\n#### Total number of documents matched: " + results.totalHits.value + ". They are (sorted by score):\n");
        for (int i = 0; i < results.totalHits.value; i++) {
          String full_path = searcher.doc(hits[i].doc).get("full_path");
          System.out.println("[" + (i + 1) + "]" + ": " + full_path);
        }
        System.out.println();
      }
    }
    reader.close();
  }
}

