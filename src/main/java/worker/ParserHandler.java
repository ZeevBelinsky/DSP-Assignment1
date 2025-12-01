package worker;

import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.DocumentPreprocessor;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.TaggedWord;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ParserHandler {

    private static LexicalizedParser parser;

    static {
        String modelPath = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";
        try {
            System.out.println("Loading Stanford Parser model...");
            parser = LexicalizedParser.loadModel(modelPath);
            System.out.println("Model loaded successfully.");
        } catch (Exception e) {
            System.err.println("Error loading Stanford Parser model: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void run(String operation, Path inputFile, Path outputFile) throws IOException {
        if (parser == null) {
            throw new IOException("Parser model failed to load. Check logs.");
        }

        StringBuilder resultBuilder = new StringBuilder();

        DocumentPreprocessor tokenizer = new DocumentPreprocessor(inputFile.toString());

        for (List<HasWord> sentence : tokenizer) {

            if (sentence.size() > 80)
                continue;

            try {
                Tree parseTree = parser.apply(sentence);

                switch (operation.toUpperCase()) {
                    case "POS":
                        ArrayList<TaggedWord> taggedWords = parseTree.taggedYield();
                        for (TaggedWord tw : taggedWords) {
                            resultBuilder.append(tw.word()).append("/").append(tw.tag()).append(" ");
                        }
                        resultBuilder.append("\n");
                        break;

                    case "CONSTITUENCY":
                        StringWriter sw = new StringWriter();
                        parseTree.pennPrint(new PrintWriter(sw));
                        resultBuilder.append(sw.toString()).append("\n");
                        break;

                    case "DEPENDENCY":
                        TreebankLanguagePack tlp = new PennTreebankLanguagePack();
                        GrammaticalStructureFactory gsf = tlp.grammaticalStructureFactory();
                        GrammaticalStructure gs = gsf.newGrammaticalStructure(parseTree);
                        resultBuilder.append(gs.typedDependenciesCollapsed().toString()).append("\n");
                        break;

                    default:
                        resultBuilder.append("Unknown analysis type: ").append(operation).append("\n");
                }
            } catch (Exception e) {
                resultBuilder.append("Error parsing sentence: ").append(e.getMessage()).append("\n");
            }
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {
            writer.write(resultBuilder.toString());
        }
    }
}