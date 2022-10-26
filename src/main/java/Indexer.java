import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.tika.Tika;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;

/**
 * Indexer class that indexes a set of documents (pdf, doc, docx, txt and more).
 */
public class Indexer {
  private Indexer() {}

  /**
   *
   * @param writer: Lucene's IndexWriter.
   * @param path: path to the documents' directory.
   * @throws IOException
   * Call recursively index_document for each document that is rooted by path
   */
  static void index_all_documents(final IndexWriter writer, Path path) throws IOException {
    // Case of a directory.
    if (Files.isDirectory(path)) {
      Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          try {
            index_document(writer, file);
          } catch (IOException ignore) {}
          return FileVisitResult.CONTINUE;
        }
      });
      // Case of a file.
    } else {
      index_document(writer, path);
    }
  }

  /**
   *
   * @param writer: Lucene's IndexWriter.
   * @param path: path to the documents' directory.
   * @throws IOException
   * Add documents to the index_path, using the writer. Internally, it stores the full_path of the document + its content.
   */
  static void index_document(IndexWriter writer, Path path) throws IOException {
    try (InputStream ignored = Files.newInputStream(path)) {
      try {
        System.out.println("#### Indexing file located at: " + path.toAbsolutePath());
        Document doc = new Document();
        doc.add(new StringField("full_path", path.toAbsolutePath().toString(), Field.Store.YES)); // <--- absolute path of the document.
        doc.add(new TextField("contents", new BufferedReader(new Tika().parse(path)))); // <--- the actual content of the document.
        writer.addDocument(doc);
      } catch (Exception e) {
        System.out.println("Could not read file located at: " + path.toAbsolutePath());
      }
    }
  }

  public static void main(String[] args) {
    String usage = "[-help HELP] [-documents_path DOCUMENTS_PATH] [-index_path INDEX_PATH] \n\n" +
            "Index all the documents located at DOCUMENTS_PATH and stores them in INDEX_PATH." + "\n" +
            "Default INDEX_PATH='./indexed_files/' and DOCUMENTS_PATH='./documents'";

    Path documents_path = Paths.get("documents/");
    Path index_path = Paths.get("indexed_files/");

    // CommandLine arguments
    for(int i = 0; i < args.length; i++) {
      if ("-documents_path".equals(args[i])) {
        documents_path = Paths.get(args[i + 1]);
        i++;
      }
      else if ("-index_path".equals(args[i])) {
        index_path = Paths.get(args[i + 1]);
        i++;
      }
      else if ("-help".equals(args[i])) {
        System.out.println(usage);
        System.exit(0);
      }
    }

    // Check if the documents_path exists and is readable
    if (!Files.isReadable(documents_path)) {
      System.out.println("#### Document directory '" + documents_path.toAbsolutePath() + "' does not exist! Make sure the documents are there!");
      System.exit(1);
    }

    try {
      Date start = new Date();
      System.out.println("#### Indexing files located at: '" + documents_path.toAbsolutePath() + "' to '" +  index_path.toAbsolutePath() + "'...");

      Directory dir = FSDirectory.open(index_path);
      Analyzer analyzer = new IRTMAnalyzer();
      IndexWriterConfig index_writer_config = new IndexWriterConfig(analyzer);
      index_writer_config.setOpenMode(OpenMode.CREATE);
      IndexWriter writer = new IndexWriter(dir, index_writer_config);
      index_all_documents(writer, documents_path);
      writer.close();

      Date end = new Date();
      System.out.println("#### Elapsed time to finish indexing: " + (end.getTime() - start.getTime()) / 1000 + " seconds");
    } catch (IOException e) {
      System.out.println("#### Got Exception: " + e.getClass());
    }
  }
}
