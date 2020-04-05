package eu.isas.searchgui.processbuilders;

import com.compomics.util.exceptions.ExceptionHandler;
import com.compomics.util.experiment.biology.aminoacids.sequence.AminoAcidPattern;
import com.compomics.util.experiment.biology.enzymes.Enzyme;
import com.compomics.util.experiment.biology.ions.NeutralLoss;
import com.compomics.util.experiment.biology.ions.impl.ReporterIon;
import com.compomics.util.experiment.biology.modifications.Modification;
import com.compomics.util.experiment.biology.modifications.ModificationFactory;
import com.compomics.util.experiment.identification.Advocate;
import com.compomics.util.parameters.identification.search.DigestionParameters;
import com.compomics.util.parameters.identification.search.SearchParameters;
import com.compomics.util.parameters.identification.tool_specific.MetaMorpheusParameters;
import com.compomics.util.pride.CvTerm;
import com.compomics.util.waiting.WaitingHandler;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

/**
 * ProcessBuilder for the MetaMorpheus search engine.
 *
 * @author Harald Barsnes
 */
public class MetaMorpheusProcessBuilder extends SearchGUIProcessBuilder {

    /**
     * The MetaMorpheus folder.
     */
    private File metaMorpheusFolder;
    /**
     * The temp folder for MetaMorpheus files.
     */
    private static String metaMorpheusTempFolderPath = null;
    /**
     * The name of the temp sub folder for MetaMorpheus files.
     */
    private static String metaMorpheusTempSubFolderName = "temp";
    /**
     * The spectrum file.
     */
    private File spectrumFile;
    /**
     * The FASTA file.
     */
    private File fastaFile;
    /**
     * The search parameters.
     */
    private SearchParameters searchParameters;
    /**
     * The advanced MetaMorpheus parameters.
     */
    private MetaMorpheusParameters metaMorpheusParameters;
    /**
     * The post translational modifications factory.
     */
    private ModificationFactory modificationFactory = ModificationFactory.getInstance();
    /**
     * Minimum peptide length.
     */
    private Integer minPeptideLength = 6;
    /**
     * Maximum peptide length.
     */
    private Integer maxPeptideLength = 30;

    /**
     * Constructor.
     *
     * @param metaMorpheusFolder the MetaMorpheus folder
     * @param searchParameters the search parameters
     * @param spectrumFile the spectrum file
     * @param outputFile the output file
     * @param fastaFile the FASTA file
     * @param waitingHandler the waiting handler
     * @param exceptionHandler the handler of exceptions
     *
     * @throws IOException thrown whenever an error occurred while reading or
     * writing a file.
     */
    public MetaMorpheusProcessBuilder(
            File metaMorpheusFolder,
            SearchParameters searchParameters,
            File spectrumFile,
            File fastaFile,
            File outputFile,
            WaitingHandler waitingHandler,
            ExceptionHandler exceptionHandler
    ) throws IOException {

        this.waitingHandler = waitingHandler;
        this.exceptionHandler = exceptionHandler;
        this.metaMorpheusFolder = metaMorpheusFolder;
        this.searchParameters = searchParameters;
        metaMorpheusParameters = (MetaMorpheusParameters) searchParameters.getIdentificationAlgorithmParameter(Advocate.metaMorpheus.getIndex());
        this.spectrumFile = spectrumFile;
        this.fastaFile = fastaFile;

        metaMorpheusTempFolderPath = getTempFolderPath(metaMorpheusFolder);

        File metaMorpheusTempFolder = new File(metaMorpheusTempFolderPath);

        if (!metaMorpheusTempFolder.exists()) {
            metaMorpheusTempFolder.mkdirs();
        }

        // make sure that the MetaMorpheus file is executable
        File metaMorpheus;
        String operatingSystem = System.getProperty("os.name").toLowerCase();
        if (operatingSystem.contains("windows")) {
            metaMorpheus = new File(metaMorpheusFolder.getAbsolutePath() + File.separator + getExecutableFileName());
        } else {
            metaMorpheus = new File(metaMorpheusFolder.getAbsolutePath() + File.separator + getExecutableFileName());
        }
        metaMorpheus.setExecutable(true);

        // create the modification lists
        File metaMorpheusModFile = new File(metaMorpheusFolder, "Mods" + File.separator + "CustomModifications.txt");
        createModificationsFile(metaMorpheusModFile);

        // create enzyme
        File metaMorpheusEnzymesFile = new File(metaMorpheusFolder, "ProteolyticDigestion" + File.separator + "proteases.tsv");
        createEnzymesFile(metaMorpheusEnzymesFile, searchParameters.getDigestionParameters());

        // create parameters file
        File metaMorpheusParametersFile = createParametersFile(searchParameters);

        // add dotnet if not on windows
        if (!operatingSystem.contains("windows")) {
            String dotNetPath = "dotnet";
            process_name_array.add(dotNetPath);
        }

        // full path to executable
        process_name_array.add(metaMorpheus.getAbsolutePath());

        // the protein sequence file
        process_name_array.add("-d");
        process_name_array.add(fastaFile.getAbsolutePath()); // @TODO: also support uniprot xml?

        // the spectrum file
        process_name_array.add("-s");
        process_name_array.add(spectrumFile.getAbsolutePath());

        // the parameters file
        process_name_array.add("-t");
        process_name_array.add(metaMorpheusParametersFile.getAbsolutePath());

        // the output folder
        process_name_array.add("-o");
        process_name_array.add(metaMorpheusTempFolderPath);

        process_name_array.trimToSize();

        // print the command to the log file
        System.out.println(System.getProperty("line.separator")
                + System.getProperty("line.separator") + "MetaMorpheus command: ");

        for (Object currentElement : process_name_array) {
            System.out.print(currentElement + " ");
        }

        System.out.println(System.getProperty("line.separator"));

        pb = new ProcessBuilder(process_name_array);
        pb.directory(metaMorpheusFolder);

        // set error out and std out to same stream
        pb.redirectErrorStream(true);
    }

    /**
     * Returns the name of the MetaMorpheus executable.
     * 
     * @return the name of the MetaMorpheus executable
     */
    public static String getExecutableFileName() {

        String operatingSystem = System.getProperty("os.name").toLowerCase();

        if (operatingSystem.contains("windows")) {
            return "CMD.exe";
        } else {
            return "CMD.dll";
        }
    }

    /**
     * Create the parameters file.
     *
     * @param searchParameters the file where to save the search parameters
     *
     * @return the parameters file
     *
     * @throws IOException exception thrown whenever an error occurred while
     * writing the configuration file
     */
    private File createParametersFile(SearchParameters searchParameters) throws IOException {

        File parameterFile = new File(metaMorpheusTempFolderPath, "SearchTask.toml");
        BufferedWriter bw = new BufferedWriter(new FileWriter(parameterFile));

        try {
            String enzymeName = "";
            Integer missedCleavages = null;

            DigestionParameters digestionParameters = searchParameters.getDigestionParameters();

            if (digestionParameters.getCleavageParameter() == DigestionParameters.CleavageParameter.wholeProtein) {
                enzymeName = "Whole Protein";
                missedCleavages = 0;
            } else if (digestionParameters.getCleavageParameter() == DigestionParameters.CleavageParameter.unSpecific) {
                enzymeName = "Unspecific";
                missedCleavages = 24;
            } else if (digestionParameters.getEnzymes().size() > 1) {
                throw new IOException("Multiple enzymes not supported!");
            } else {
                Enzyme enzyme = digestionParameters.getEnzymes().get(0);
                enzymeName = enzyme.getName();
                missedCleavages = digestionParameters.getnMissedCleavages(enzymeName);
            }

            minPeptideLength = metaMorpheusParameters.getMinPeptideLength();
            maxPeptideLength = metaMorpheusParameters.getMaxPeptideLength();

            // task type
            bw.write("TaskType = \"Search\"" + System.getProperty("line.separator"));
            bw.newLine();

            //////////////////////////
            // search parameters
            //////////////////////////
            bw.write("[SearchParameters]" + System.getProperty("line.separator"));
            bw.write("DisposeOfFileWhenDone = true" + System.getProperty("line.separator"));
            bw.write("DoParsimony = true" + System.getProperty("line.separator")); // NOTE: if false, the mzid file is not created!
            bw.write("ModPeptidesAreDifferent = false" + System.getProperty("line.separator"));
            bw.write("NoOneHitWonders = false" + System.getProperty("line.separator"));
            bw.write("MatchBetweenRuns = false" + System.getProperty("line.separator"));
            bw.write("Normalize = false" + System.getProperty("line.separator"));
            bw.write("QuantifyPpmTol = 5.0" + System.getProperty("line.separator"));
            bw.write("DoHistogramAnalysis = false" + System.getProperty("line.separator"));
            bw.write("SearchTarget = true" + System.getProperty("line.separator"));
            bw.write("DecoyType = \"None\"" + System.getProperty("line.separator"));
            bw.write("MassDiffAcceptorType = \"OneMM\"" + System.getProperty("line.separator"));
            bw.write("WritePrunedDatabase = false" + System.getProperty("line.separator"));
            bw.write("KeepAllUniprotMods = true" + System.getProperty("line.separator"));
            bw.write("DoLocalizationAnalysis = true" + System.getProperty("line.separator"));
            bw.write("DoQuantification = false" + System.getProperty("line.separator"));
            bw.write("SearchType = \"Classic\"" + System.getProperty("line.separator"));
            bw.write("LocalFdrCategories = [\"FullySpecific\"]" + System.getProperty("line.separator"));
            bw.write("MaxFragmentSize = 30000.0" + System.getProperty("line.separator"));
            bw.write("HistogramBinTolInDaltons = 0.003" + System.getProperty("line.separator"));
            bw.write("MaximumMassThatFragmentIonScoreIsDoubled = 0.0" + System.getProperty("line.separator"));
            bw.write("WriteMzId = true" + System.getProperty("line.separator"));
            bw.write("WritePepXml = false" + System.getProperty("line.separator"));
            bw.write("WriteDecoys = true" + System.getProperty("line.separator"));
            bw.write("WriteContaminants = true" + System.getProperty("line.separator"));
            bw.newLine();

            //////////////////////////////////
            // modification out parameters
            //////////////////////////////////
            bw.write("[SearchParameters.ModsToWriteSelection]" + System.getProperty("line.separator"));
            bw.write("'N-linked glycosylation' = 3" + System.getProperty("line.separator"));
            bw.write("'O-linked glycosylation' = 3" + System.getProperty("line.separator"));
            bw.write("'Other glycosylation' = 3" + System.getProperty("line.separator"));
            bw.write("'Common Biological' = 3" + System.getProperty("line.separator"));
            bw.write("'Less Common' = 3" + System.getProperty("line.separator"));
            bw.write("Metal = 3" + System.getProperty("line.separator"));
            bw.write("'2+ nucleotide substitution' = 3" + System.getProperty("line.separator"));
            bw.write("'1 nucleotide substitution' = 3" + System.getProperty("line.separator"));
            bw.write("UniProt = 2" + System.getProperty("line.separator"));
            bw.newLine();

            //////////////////////////
            // common parameters
            //////////////////////////
            bw.write("[CommonParameters]" + System.getProperty("line.separator"));
            bw.write("MaxThreadsToUsePerFile = 3" + System.getProperty("line.separator"));

            // fixed modifications
            bw.write("ListOfModsFixed = \""); // @TODO: merge the fixed and variable writing code!

            ArrayList<String> fixedModifications = searchParameters.getModificationParameters().getFixedModifications();

            for (int i = 0; i < fixedModifications.size(); i++) {

                if (i > 0) {
                    bw.write("\t\t");
                }

                String modName = fixedModifications.get(i);
                String tempModName = modName.replaceAll(" of ", " off "); // temporary fix given that MetaMorpheus kicks out ptms with " of " in the name...

                AminoAcidPattern aminoAcidPattern = modificationFactory.getModification(modName).getPattern();

                if (!aminoAcidPattern.getAminoAcidsAtTarget().isEmpty()) {
                    for (Character residue : aminoAcidPattern.getAminoAcidsAtTarget()) {
                        bw.write("SearchGUI\t" + tempModName + " on " + residue);
                    }
                } else {
                    bw.write("SearchGUI\t" + tempModName + " on X");
                }
            }

            bw.write("\"" + System.getProperty("line.separator"));

            // variable modifications
            bw.write("ListOfModsVariable = \"");

            ArrayList<String> variableModifications = searchParameters.getModificationParameters().getVariableModifications();

            for (int i = 0; i < variableModifications.size(); i++) {

                if (i > 0) {
                    bw.write("\t\t");
                }

                String modName = variableModifications.get(i);
                String tempModName = modName.replaceAll(" of ", " off "); // temporary fix given that MetaMorpheus kicks out ptms with " of " in the name...

                AminoAcidPattern aminoAcidPattern = modificationFactory.getModification(modName).getPattern();

                if (aminoAcidPattern != null) {

                    if (!aminoAcidPattern.getAminoAcidsAtTarget().isEmpty()) {
                        for (Character residue : aminoAcidPattern.getAminoAcidsAtTarget()) {
                            bw.write("SearchGUI\t" + tempModName + " on " + residue);
                        }
                    } else {
                        bw.write("SearchGUI\t" + tempModName + " on X");
                    }

                }
            }

            bw.write("\"" + System.getProperty("line.separator"));
            bw.write("DoPrecursorDeconvolution = true" + System.getProperty("line.separator"));
            bw.write("UseProvidedPrecursorInfo = true" + System.getProperty("line.separator"));
            bw.write("DeconvolutionIntensityRatio = 3.0" + System.getProperty("line.separator"));
            bw.write("DeconvolutionMaxAssumedChargeState = 12" + System.getProperty("line.separator"));
            bw.write("DeconvolutionMassTolerance = \"Â±4.0000 PPM\"" + System.getProperty("line.separator"));
            bw.write("TotalPartitions = 1" + System.getProperty("line.separator"));

            // fragment and precursor tolerances
            bw.write("ProductMassTolerance = \"Â±" + searchParameters.getFragmentIonAccuracy());
            if (searchParameters.getFragmentAccuracyType() == SearchParameters.MassAccuracyType.PPM) {
                bw.write(" PPM\"" + System.getProperty("line.separator"));
            } else {
                bw.write(" Absolute\"" + System.getProperty("line.separator"));
            }
            bw.write("PrecursorMassTolerance = \"Â±" + searchParameters.getPrecursorAccuracy());
            if (searchParameters.getPrecursorAccuracyType() == SearchParameters.MassAccuracyType.PPM) {
                bw.write(" PPM\"" + System.getProperty("line.separator"));
            } else {
                bw.write(" Absolute\"" + System.getProperty("line.separator"));
            }

            bw.write("AddCompIons = false" + System.getProperty("line.separator"));
            bw.write("ScoreCutoff = 5.0" + System.getProperty("line.separator"));
            bw.write("ReportAllAmbiguity = true" + System.getProperty("line.separator"));
            bw.write("NumberOfPeaksToKeepPerWindow = 200" + System.getProperty("line.separator"));
            bw.write("MinimumAllowedIntensityRatioToBasePeak = 0.01" + System.getProperty("line.separator"));
            bw.write("NormalizePeaksAccrossAllWindows = false" + System.getProperty("line.separator"));
            bw.write("TrimMs1Peaks = false" + System.getProperty("line.separator"));
            bw.write("TrimMsMsPeaks = true" + System.getProperty("line.separator"));
            bw.write("UseDeltaScore = false" + System.getProperty("line.separator"));
            bw.write("QValueOutputFilter = 1.0" + System.getProperty("line.separator"));
            bw.write("CustomIons = []" + System.getProperty("line.separator"));
            bw.write("AssumeOrphanPeaksAreZ1Fragments = true" + System.getProperty("line.separator"));
            bw.write("MaxHeterozygousVariants = 4" + System.getProperty("line.separator"));
            bw.write("MinVariantDepth = 1" + System.getProperty("line.separator"));
            bw.write("DissociationType = \"HCD\"" + System.getProperty("line.separator"));
            bw.write("ChildScanDissociationType = \"Unknown\"" + System.getProperty("line.separator"));
            bw.newLine();

            //////////////////////////
            // digestion parameters
            //////////////////////////
            bw.write("[CommonParameters.DigestionParams]" + System.getProperty("line.separator"));
            bw.write("MaxMissedCleavages = " + missedCleavages + System.getProperty("line.separator"));
            bw.write("InitiatorMethionineBehavior = \"Variable\"" + System.getProperty("line.separator"));
            bw.write("MinPeptideLength = " + minPeptideLength + System.getProperty("line.separator"));
            bw.write("MaxPeptideLength = " + maxPeptideLength + System.getProperty("line.separator"));
            bw.write("MaxModificationIsoforms = 1024" + System.getProperty("line.separator"));
            bw.write("MaxModsForPeptide = 2" + System.getProperty("line.separator"));
            bw.write("Protease = \"" + enzymeName + "\"" + System.getProperty("line.separator"));
            bw.write("SearchModeType = \"Full\"" + System.getProperty("line.separator"));
            bw.write("FragmentationTerminus = \"Both\"" + System.getProperty("line.separator")); // Both, N, C
            bw.write("SpecificProtease = \"" + enzymeName + "\"" + System.getProperty("line.separator"));
            bw.write("GeneratehUnlabeledProteinsForSilac = true" + System.getProperty("line.separator"));

        } finally {
            bw.close();
        }

        return parameterFile;
    }

    @Override
    public String getType() {
        return "MetaMorpheus";
    }

    @Override
    public String getCurrentlyProcessedFileName() {
        return spectrumFile.getName();
    }

    /**
     * Returns the temp folder path. Instantiates if null.
     *
     * @param metaMorpheusFolder the MetaMorpheus folder
     *
     * @return the temp folder path
     */
    public static String getTempFolderPath(File metaMorpheusFolder) {

        if (metaMorpheusTempFolderPath == null) {

            metaMorpheusTempFolderPath = metaMorpheusFolder.getAbsolutePath()
                    + File.separator + metaMorpheusTempSubFolderName;

        }

        return metaMorpheusTempFolderPath;

    }

    /**
     * Creates the MetaMorpheus enzymes file.
     *
     * @param metaMorpheusEnzymesFile the MetaMorpheus enzyme file
     * @param digestionPreferences the digestion preferences
     *
     * @throws IOException if the enzymes file could not be written
     */
    private void createEnzymesFile(File metaMorpheusEnzymesFile, DigestionParameters digestionPreferences) throws IOException {

        try {

            BufferedWriter bw = new BufferedWriter(new FileWriter(metaMorpheusEnzymesFile));

            try {

                // write the header
                bw.write("Name\t"
                        + "Sequences Inducing Cleavage\t"
                        + "Sequences Preventing Cleavage\t"
                        + "Cleavage Terminus\t"
                        + "Cleavage Specificity\t"
                        + "PSI-MS Accession Number\t"
                        + "PSI-MS Name\t"
                        + "Site Regular Expression\t"
                        + "Notes");
                bw.newLine();

                // dummy trypsin, as otherwise metamorpheus refuses to start... // @TODO: figure out why!
                bw.write("trypsin\tK|,R|\t\t\tfull\tMS:1001313\tTrypsin/P\t(?<=[KR])");
                bw.newLine();

                if (digestionPreferences.getCleavageParameter() == DigestionParameters.CleavageParameter.wholeProtein) {
                    bw.write("Whole Protein\t\t\t\tnone\tMS:1001955\tno cleavage");
                    bw.newLine();
                } else if (digestionPreferences.getCleavageParameter() == DigestionParameters.CleavageParameter.unSpecific) {
                    bw.write("Unspecific\tX|\t\t\tfull\tMS:1001956\tunspecific cleavage");
                    bw.newLine();
                } else if (digestionPreferences.getEnzymes().size() > 1) {
                    throw new IOException("Multiple enzymes not supported!");
                } else {

                    Enzyme enzyme = digestionPreferences.getEnzymes().get(0);

                    String enzymeName = enzyme.getName();

                    // name
                    bw.write(enzymeName + "\t");

                    // sequence inducing cleavage 
                    String cleavageSite = "";

                    if (!enzyme.getAminoAcidBefore().isEmpty()) {
                        for (Character cleaveCharacter : enzyme.getAminoAcidBefore()) {
                            if (!enzyme.getRestrictionAfter().isEmpty()) {
                                for (Character restrictCharacter : enzyme.getRestrictionAfter()) {
                                    if (!cleavageSite.isEmpty()) {
                                        cleavageSite += ",";
                                    }
                                    cleavageSite += cleaveCharacter + "|[" + restrictCharacter + "]";
                                }
                            } else {
                                if (!cleavageSite.isEmpty()) {
                                    cleavageSite += ",";
                                }
                                cleavageSite += cleaveCharacter + "|";
                            }
                        }
                    } else {
                        for (Character cleaveCharacter : enzyme.getAminoAcidAfter()) {
                            if (!enzyme.getRestrictionBefore().isEmpty()) {
                                for (Character restrictCharacter : enzyme.getRestrictionBefore()) {
                                    if (!cleavageSite.isEmpty()) {
                                        cleavageSite += ",";
                                    }
                                    cleavageSite += "[" + restrictCharacter + "]|" + cleaveCharacter;
                                }
                            } else {
                                if (!cleavageSite.isEmpty()) {
                                    cleavageSite += ",";
                                }
                                cleavageSite += "|" + cleaveCharacter;
                            }
                        }
                    }

                    bw.write(cleavageSite + "\t\t\t");

                    // cleavage specificity
                    DigestionParameters.Specificity specificity = digestionPreferences.getSpecificity(enzymeName);
                    if (null != specificity) {
                        if (specificity == DigestionParameters.Specificity.specific) {
                            bw.write("full\t");
                        } else {
                            bw.write("semi\t");
                        }
                    }

                    // psi-ms accesion number and name
                    if (enzyme.getCvTerm() != null) {
                        bw.write(enzyme.getCvTerm().getAccession() + "\t");
                        bw.write(enzyme.getCvTerm().getName() + "\t");
                    } else {
                        bw.write("\t\t");
                    }

                    // site regular expresssion, e.g. for chymotrypsin (?<=[FYWL])(?!P)
                    // bw.write("(?<=[FYWL])(?!P)"); // @TODO: add regular expressions?
                    bw.write("\t");

                    // notes
                    bw.write("\t");

                    bw.newLine();
                }

            } finally {
                bw.close();
            }
        } catch (IOException ioe) {
            throw new IOException("Could not create MetaMorpheus enzymes file. Unable to write file: '" + ioe.getMessage() + "'.");
        }
    }

    /**
     * Creates the MetaMorpheus modifications file.
     *
     * @param metaMorpheusModFile the MetaMorpheus modification file
     *
     * @throws IOException if the modification file could not be created
     */
    private void createModificationsFile(File metaMorpheusModFile) throws IOException {

        try {

            BufferedWriter bw = new BufferedWriter(new FileWriter(metaMorpheusModFile));

            try {
                bw.write("Custom Modifications\n");

                // add the fixed modifications
                ArrayList<String> fixedModifications = searchParameters.getModificationParameters().getFixedModifications();

                for (String modName : fixedModifications) {
                    bw.write(getModificationFormattedForMetaMorpheus(modName));
                }

                // add the variable modifications
                ArrayList<String> variableModifications = searchParameters.getModificationParameters().getVariableModifications();

                for (String modName : variableModifications) {
                    bw.write(getModificationFormattedForMetaMorpheus(modName));
                }

            } finally {
                bw.close();
            }
        } catch (IOException ioe) {

            throw new IllegalArgumentException("Could not create MetaMorpheus modifications file. Unable to write file: '" + ioe.getMessage() + "'.");

        }
    }

    /**
     * Get the given modification as a string in the MetaMorpheus format.
     *
     * @param modName the utilities name of the modification
     * @param fixed if the modification is fixed or not
     * @return the given modification as a string in the MetaMorpheus format
     */
    private String getModificationFormattedForMetaMorpheus(String modName) {

        Modification modification = modificationFactory.getModification(modName);

        // Example:
        //  ID   Phosphorylation
        //  TG   Y
        //  PP   Anywhere.
        //  NL   HCD:H0 or HCD:H3 O4 P1
        //  MT   Common Biological
        //  CF   H1 O3 P1
        //  DI   HCD:C8 H10 N1 O4 P1
        //  DR   Unimod; 21.
        //  //
        // the id
        String tempModName = modification.getName().replaceAll(" of ", " off "); // temporary fix given that MetaMorpheus kicks out ptms with " of " in the name...
        String modificationAsString = "ID   " + tempModName + "\n";

        // the targeted amino acids
        modificationAsString += "TG   ";

        String aminoAcidsAtTarget = "";
        AminoAcidPattern aminoAcidPattern = modification.getPattern();

        if (aminoAcidPattern != null) {
            for (Character aa : modification.getPattern().getAminoAcidsAtTarget()) {
                if (!aminoAcidsAtTarget.isEmpty()) {
                    aminoAcidsAtTarget += " or ";
                }
                aminoAcidsAtTarget += aa;
            }
        }

        if (aminoAcidsAtTarget.length() == 0) {
            aminoAcidsAtTarget = "X";
        }

        modificationAsString += aminoAcidsAtTarget + "\n";

        // the type of the modification
        modificationAsString += "PP   ";

        String position = "";
        switch (modification.getModificationType()) {
            case modaa:
                position = "Anywhere.";
                break;
            case modc_protein:
            case modcaa_protein:
                position = "C-terminal.";
                break;
            case modc_peptide:
            case modcaa_peptide:
                position = "Peptide C-terminal.";
                break;
            case modn_protein:
            case modnaa_protein:
                position = "N-terminal.";
                break;
            case modn_peptide:
            case modnaa_peptide:
                position = "Peptide N-terminal.";
                break;
            default:
                throw new UnsupportedOperationException("Modification type " + modification.getModificationType() + " not supported.");
        }

        modificationAsString += position + "\n";

        // the neutral losses
        for (NeutralLoss tempNeutralLoss : modification.getNeutralLosses()) {
            modificationAsString += "NL   " + tempNeutralLoss.getComposition().getStringValue(true, false, true, true, true) + "\n";
        }

        // the modification type
        modificationAsString += "MT   SearchGUI\n"; // @TODO: make us of the type?

        // chemical formula
        modificationAsString += "CF   " + modification.getAtomChainAdded().getStringValue(true, false, true, true, true) + "\n";

        // diagnostic ions
        for (ReporterIon tempReporterIon : modification.getReporterIons()) {
            modificationAsString += "DI   " + tempReporterIon.getAtomicComposition().getStringValue(true, false, true, true, true) + "\n";
        }

        // add unimod name
        CvTerm cvTerm = modification.getUnimodCvTerm();
        if (cvTerm != null) {
            String completeAccession = cvTerm.getAccession();
            modificationAsString += "DR   Unimod; " + completeAccession.substring(7) + ".\n";
        }

        modificationAsString += "//\n";

        return modificationAsString;
    }
}