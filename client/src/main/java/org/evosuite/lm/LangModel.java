/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.lm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a language model, a set of bigrams, unigrams and associated
 * log-probabilities.
 */
public class LangModel {

    private static final Logger logger = LoggerFactory.getLogger(LangModel.class);

    // class variables
    // Hashes storing various Language Model probabilities
    /**
     * Probability of a unigram occurring.
     */
    private final Map<String, Double> unigramProbs = new HashMap<>();
    /**
     * Unigram backoff probabilities (used in bigram probability estimation).
     */
    private final Map<String, Double> unigramBackoffProbs = new HashMap<>();
    /**
     * Probability that Unigram2 follows Unigram1, where each key is of the form "Unigram1 Unigram2".
     */
    private final Map<String, Double> bigramProbs = new HashMap<>();

    //Sentinel unigram values:
    public static final String START_OF_STRING = "<s>";
    public static final String END_OF_STRING = "</s>";
    public static final String START_NEW_WORD = "<w>";

    private double unknownCharProb = 0;


    // Hashes to store most probable next characters in bigram
    /**
     * Mapping of the nth most likely unigrams to follow each unigram.
     * Encoded as: <code>(unigram)(n)> -> (unigram)</code>
     */
    private final HashMap<String, String> contextChar = new HashMap<>();
    /**
     * Mapping of the probability of the nth most likely unigram to follow each unigram.
     * Encoded as: <code>(unigram)(n)> -> (log_probability)</code>
     */
    private final HashMap<String, Double> contextProb = new HashMap<>();

    // Maximum number of characters to predict for each bigram
    int predictedChars = 10;

    // Constructors
    // Read in data from language model to be manipulated later
    // Takes language model file as argument

    /**
     * Load the language model.
     *
     * @param lmFileName path to a language model file.
     * @throws IOException if the model file can't be found or read.
     */
    public LangModel(String lmFileName) throws IOException {


        // Flag to indicate length of n-grams currently being read (0 == read
        // nothing)
        int ngramLen = 0; //size of the n-grams we're reading (i.e. ngram_len = 5 implies 5-grams).

        InputStream fstream = LangModel.class.getClassLoader().getResourceAsStream(lmFileName);
        // FileInputStream fstream = new FileInputStream(lmFileName);

        DataInputStream in = new DataInputStream(fstream);
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        String strLine;

        double highestUnigramProb = 0;

        // Read file line by line
        while ((strLine = br.readLine()) != null) {
            Pattern ngramLenP = Pattern.compile("(\\d+)-grams:");
            Matcher matchNgramLen = ngramLenP.matcher(strLine);
            //does line match (\d+)-grams: ?
            if (matchNgramLen.find()) {
                ngramLen = Integer.parseInt(matchNgramLen.group(1));

            } else if (ngramLen == 1) {
                //We're looking at unigrams;
                Pattern unigramPattern = Pattern
                        .compile("([-0-9\\.]+)\\s*(\\S+)\\s*([-0-9\\.]+)");
                // Match with <floating point number> <one or more chars> <floating point number>
                //                        |                   |                 +------ backoff probability
                //                        |                   +------------------------ unigram
                //                        +-------------------------------------------- unigram probability
                Matcher matchUnigram = unigramPattern.matcher(strLine);
                if (matchUnigram.find()) {

                    double unigramProb = Double.parseDouble(matchUnigram
                            .group(1));
                    String unigram = matchUnigram.group(2);
                    double unigramBackoffProb = Double
                            .parseDouble(matchUnigram.group(3));

                    unigramProbs.put(unigram, unigramProb);
                    unigramBackoffProbs.put(unigram, unigramBackoffProb);

                    if (unigramProb < unknownCharProb) {
                        unknownCharProb = unigramProb;
                    } // if
                    if (unigramProb > highestUnigramProb) {
                        highestUnigramProb = unigramProb;
                    } //if

                } // if

            } else if (ngramLen == 2) {
                Pattern bigramPattern = Pattern.compile("([-0-9\\.]+)\\s*(\\S+) (\\S+)");
                //Match line with <floating point number> <one or more chars> <one or more chars>
                //                            |                   |                    +---- end char of bigram
                //                            |                   +------------------------- start char of bigram
                //                            +--------------------------------------------- bigram probability
                Matcher matchBigram = bigramPattern.matcher(strLine);
                if (matchBigram.find()) {
                    double bigramProb = Double.parseDouble(matchBigram
                            .group(1));
                    String bigramStart = matchBigram.group(2);
                    String bigramEnd = matchBigram.group(3);
                    String bigram = bigramStart + " " + bigramEnd;

                    bigramProbs.put(bigram, bigramProb);

                } // if

            } // if/else
        } // while
        // Close the input stream
        in.close();

        ValueComparator bvc = new ValueComparator(bigramProbs);
        TreeMap<String, Double> sortedBigramProbs = new TreeMap<>(
                bvc);

        //Store bigrams sorted by probability:
        sortedBigramProbs.putAll(bigramProbs);

        // Regular expressions setup
        Pattern contextPattern = Pattern.compile("(\\S+) (\\S+)");

        //Go through each bigram in order (most likely first) and build a
        // table of the predicted_chars most likely characters to follow each character.
        for (Map.Entry<String, Double> entry : sortedBigramProbs.entrySet()) {
            Matcher matchContext = contextPattern.matcher(entry.getKey());
            if (matchContext.find()) {
                String pre = matchContext.group(1);
                String middle = matchContext.group(2);

                // Add to hash (do this by starting counter at 0 and then
                // testing hash and
                // filling first empty slot. If no empty slot found then value
                // is not stored.
                for (int c = 0; c < predictedChars; c++) {
                    String key = pre + c;
                    if (!contextChar.containsKey(key)) {
                        contextChar.put(key, middle);
                        contextProb.put(key, entry.getValue());
                        break;
                    } // if
                } // for
            } // if

        } // for

        // Print out as sanity check
        //for (Map.Entry<String, String> entry : context_char.entrySet()) {
        // logger.debug("Key = " + entry.getKey() + ", Value = " +
        // entry.getValue());
        //}

    } // LangModel

    // Method which returns language model score for string str Splits
    // string into bigrams and looks up the probability for each. If
    // the bigram isn't found then backs off to use the unigram and
    // backoff probabilities str is string for which score is
    // computed, verbose is flag indicating whether to print out
    // details about how this score is computed

    /**
     * Splits a string into bigrams and calculates the language model score.
     * For each bigram, it looks up the probability. The score is the geometric mean
     * of the probability of each bigram in the string according to the model.
     *
     * <p>If a given bigram isn't in the model, unigrams are used to estimate the probability
     * of the bigram instead.
     *
     * @param str     String for which to compute the score
     * @param verbose whether to print information
     * @return the language model score
     */
    public double score(String str, boolean verbose) {

        if (verbose) {
            logger.debug("String is {}", str);
        } // if

        double logProb = 0;

        // Get length of string
        int numChars = str.length();

        // Break string down into bigrams
        for (int i = -1; i < (numChars - 1); i++) {
            String firstChar;
            String secondChar;
            if (i == -1) {
                firstChar = "<s>";
                secondChar = str.substring(0, 1);
            } else {
                firstChar = str.substring(i, i + 1);
                secondChar = str.substring(i + 1, i + 2);
            } // if/else

            if (firstChar.equals(" ")) {
                firstChar = "<w>";
            } // if
            if (secondChar.equals(" ")) {
                secondChar = "<w>";
            } // if
            String bigram = firstChar + " " + secondChar;

            if (verbose) {
                logger.debug("Bigram is {}", bigram);
            } // if

            // Get negative log likelihood for each bigram
            // (Either get directly or estimate using backoff)
            if (bigramProbs.containsKey(bigram)) {
                // Get direct bigram probabilities
                double bigramProb = bigramProbs.get(bigram);
                logProb = logProb + bigramProb;
                if (verbose) {
                    logger.debug("Direct bigram prob: {}\n", Math.pow(10, bigramProb));
                } // if
            } else if (unigramProbs.containsKey(secondChar) && unigramBackoffProbs.containsKey(firstChar)) {

                // Otherwise split into unigrams and do backoff
                double unigramBackoffProb = unigramBackoffProbs
                        .get(firstChar);
                logProb = logProb + unigramBackoffProb;
                // logger.debug("Unigram ("+first_char+") backoff prob: "+unigram_backoff_prob);


                double unigramProb = unigramProbs.get(secondChar);
                logProb = logProb + unigramProb;

                if (verbose) {
                    double bigramProb = unigramBackoffProb + unigramProb;
                    logger.debug("Inferred bigram prob: {} (formed from unigram probs {}: {} and {}: {})\n",
                            Math.pow(10, bigramProb),
                            firstChar, Math.pow(10, unigramBackoffProb),
                            secondChar, Math.pow(10, unigramProb));
                } // if
            } else {
                //Note: we don't penalise strings containing weird (non-printable) characters.
                //If we hit one (this block), just do nothing.
                //throw new RuntimeException("Language Model can't give predictions for bigram " + bigram);

                logProb += unknownCharProb;

            }

        } // for

        // Convert log probs to probs and take geometric mean
        //TODO: if none of the chars are accepted bigrams or unigrams this function used to return 1.0...
        //did averaging the prob (rather than exponentiating the average log-prob) break anything?
        double avgProb = Math.pow(10, logProb / ((double) numChars));

        return avgProb;

    } // score

    /**
     * Convenience method for {@link #score(String, boolean)} with verbose flag set to false.
     *
     * @param str the string to score
     * @return the score
     */
    public double score(String str) {

        return score(str, false);

    } // score

    /**
     * Returns the nth most likely character to follow pre.
     *
     * @param pre the preceding character
     * @param n the rank of likelihood (0 for most likely)
     * @return the nth most likely character to follow pre
     */
    public String predict_char(String pre, int n) {

        if (pre.equals(" ")) {
            pre = "<w>";
        }

        String key = pre + n;

        if (n < 0 || n > predictedChars) {
            return null;
        } else {
            return contextChar.get(key);
        } // if/else

    } // predict_char

    /**
     * Returns the nth most likely character that a string will start with.
     *
     * @param n the rank of likelihood
     * @return the nth most likely character that a string will start with
     */
    public String predict_char(int n) {

        return predict_char("<s>", n);

    } // predict_char

    /**
     * Method which returns the probability of the nth most likely character, given a
     * preceeding character (pre). Use in combination with the predict_char methods.
     *
     * @param pre the preceding character
     * @param n the rank of likelihood
     * @return the probability of the nth character that is most likely to appear
     */
    public double predict_char_prob(String pre, int n) {

        if (n < 0 || n > predictedChars) {
            return 0;
        }

        if (pre.equals(" ")) {
            pre = "<w>";
        }

        String key = pre + n;
        Double prob = contextProb.get(key);

        if (prob != null) {
            prob = Math.pow(10, prob);
            return prob;
        } // if

        return 0.0;

    } // predict_char_prob

    /**
     * Method which returns the probability of the nth most likley character at
     * the start of a sentence.
     * N.B. Simply calls predict_char_prob/2 with preceeding char set to "&lt;s&gt;".
     *
     * @param n the rank of likelihood
     * @return the probability associated with the nth most likely character to start a sentence
     */
    public double predict_char_prob(int n) {

        return predict_char_prob("<s>", n);

    } // predict_char_prob

    /**
     * Checks if the character is a magic character (start/end of string or new word).
     *
     * @param character the character to check
     * @return true if magic, false otherwise
     */
    public boolean isMagicChar(String character) {

        return character.equals(START_NEW_WORD) || character.equals(END_OF_STRING) || character.equals(START_OF_STRING);
    }

    /**
     * Checks if the character indicates the end of a sentence.
     *
     * @param character the character to check
     * @return true if end of sentence, false otherwise
     */
    public boolean isEndOfSentence(String character) {
        return character.equals(END_OF_STRING);
    }


} // LangModel
