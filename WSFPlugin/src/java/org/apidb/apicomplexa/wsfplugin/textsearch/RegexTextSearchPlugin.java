/**
 * 
 */
package org.apidb.apicomplexa.wsfplugin.textsearch;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.gusdb.wsf.plugin.AbstractPlugin;
import org.gusdb.wsf.plugin.WsfRequest;
import org.gusdb.wsf.plugin.WsfResponse;
import org.gusdb.wsf.plugin.WsfServiceException;

/**
 * @author John I
 * @created Aug 23, 2006
 */
@Deprecated
public class RegexTextSearchPlugin extends AbstractPlugin {

    private String scriptDir;

    private static final String PROPERTY_FILE = "textSearch-config.xml";

    // required parameter definition
    public static final String PARAM_TEXT_EXPRESSION = "text_expression";
    // public static final String PARAM_CASE_INDEPENDENT = "case_independent";
    public static final String PARAM_DATASETS = "text_fields";
    public static final String PARAM_MAX_PVALUE = "max_pvalue";
    public static final String PARAM_SPECIES_NAME = "text_search_organism";
    public static final String PARAM_WHOLE_WORDS = "whole_words";

    public static final String COLUMN_GENE_ID = "GeneID";
    public static final String COLUMN_DATASETS = "Datasets";
    public static final String COLUMN_PROJECT_ID = "ProjectId";

    // field definition
    private static final String FIELD_DATA_DIR = "DataDir";
    private static final String FIELD_SCRIPT_DIR = "ScriptDir";
    private static final String FIELD_PROJECT_ID = "ProjectID";

    private File dataDir;
    private String projectId;

    // private String sourceIdRegex;
    // private int maxLen;

    /**
     * @throws WsfServiceException
     * 
     */
    public TextSearchPlugin() throws WsfServiceException {
        super(PROPERTY_FILE);
    }

    // load properties

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.plugin.AbstractPlugin#initialize(java.util.Map)
     */
    @Override
    public void initialize(Map<String, Object> context)
            throws WsfServiceException {
        super.initialize(context);

        // load properties
        String dir = getProperty(FIELD_DATA_DIR);
        if (dir == null)
            throw new WsfServiceException(
                    "The required field in property file is missing: "
                            + FIELD_DATA_DIR);
        dataDir = new File(dir);
        logger.debug("constructor(): dataDir: " + dataDir.getName() + "\n");

        scriptDir = getProperty(FIELD_SCRIPT_DIR);
        projectId = getProperty(FIELD_PROJECT_ID);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.WsfPlugin#getRequiredParameters()
     */
    public String[] getRequiredParameterNames() {
        return new String[] { PARAM_TEXT_EXPRESSION, PARAM_DATASETS };
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.WsfPlugin#getColumns()
     */
    public String[] getColumns() {
        return new String[] { COLUMN_GENE_ID, COLUMN_DATASETS,
                COLUMN_PROJECT_ID };
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.plugin.WsfPlugin#validateParameters(java.util.Map)
     */
    public void validateParameters(WsfRequest request)
            throws WsfServiceException {
    // do nothing in this plugin

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.WsfPlugin#execute(java.util.Map, java.lang.String[])
     */
    public WsfResponse execute(WsfRequest request) throws WsfServiceException {
        logger.info("Invoking TextSearchPlugin...");

        // get parameters
        Map<String, String> params = request.getParams();
        String datasets = params.get(PARAM_DATASETS);
        String whole_words = params.get(PARAM_WHOLE_WORDS);
        String textExpression = rewriteExpression(
                params.get(PARAM_TEXT_EXPRESSION), whole_words);
        String maxPvalue = params.get(PARAM_MAX_PVALUE);
        String species_name = params.get(PARAM_SPECIES_NAME);

        // String caseIndependent = params.get(PARAM_CASE_INDEPENDENT);
        String caseIndependent = "-i"; // always case-independent

        Map<String, Set<String>> matches = new HashMap<String, Set<String>>();

        if (species_name == null) {
            species_name = "";
        }

        // iterate through datasets
        int signal = 0;
        String[] ds = datasets.split(",");
        for (String dataset : ds) {
            dataset = dataset.trim();
            String datasetName = dataset.replaceAll("^.*_", "").replaceAll(
                    ".txt", "");
            String cmd = scriptDir + "/filterByValue -n " + maxPvalue + " < "
                    + dataDir + "/" + dataset + " | " + scriptDir
                    + "/filterByValue -s " + species_name + " | egrep "
                    + caseIndependent + " " + textExpression + " | cut -f1 ";

            logger.info("\ncommand line = \"" + cmd + "\"\n\n");

            // make it a string array to fool exec() into working
            // String[] cmds = new String[] {cmd.toString(), "|", "cut", "-f1",
            // "|", "sort", "-u"};
            String[] cmds = new String[] { "bash", "-c", cmd };

            // run it
            try {
                StringBuffer output = new StringBuffer();
                signal = invokeCommand(cmds, output, 10 * 60);
                // System.out.println("output is " + output);
                logger.debug("output is " + output);
                // long end = System.currentTimeMillis();
                // logger.info("Invocation takes: " + ((end - start) / 1000.0)
                // + " seconds");

                if (signal != 0)
                    throw new WsfServiceException("The invocation is failed: "
                            + output);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new ByteArrayInputStream(
                                output.toString().getBytes())));
                String nameIn;
                while ((nameIn = reader.readLine()) != null) {

                    logger.debug("match GeneID: " + nameIn);

                    if (!matches.containsKey(nameIn)) {
                        matches.put(nameIn, new HashSet<String>());
                    }
                    matches.get(nameIn).add(datasetName);
                }

            } catch (Exception ex) {
                throw new WsfServiceException(ex);
            }
        }
        // construct results
        String[][] result = prepareResult(matches, request.getOrderedColumns());
        WsfResponse wsfResult = new WsfResponse();
        wsfResult.setResult(result);
        wsfResult.setSignal(signal);
        return wsfResult;
    }

    private String rewriteExpression(String expression, String whole_words) {
        String newExpression = expression;
        ;

        String[][] replacement = { { "\\.", "\\\\." }, { "/", "\\\\/" },
                { "\\|", "\\\\|" }, { "\\*", ".*" } };
        for (int i = 0; i < replacement.length; i++)
            newExpression = newExpression.replaceAll(replacement[i][0],
                    replacement[i][1]);

        if (newExpression.substring(0, 1).equals("^")) {
            if (whole_words.equals("true")) {
                newExpression = "	" + newExpression.substring(1)
                        + "[^[:alnum:]]";
            } else {
                newExpression = "	" + newExpression.substring(1);
            }
        } else {
            if (whole_words.equals("true")) {
                newExpression = "[^[:alnum:]]" + newExpression + "[^[:alnum:]]";
            } else {
                newExpression = "	.*" + newExpression;
            }
        }

        logger.debug("rewrote \"" + expression + "\" to \"" + newExpression
                + "\"");

        return newExpression;

    }

    private String[][] prepareResult(Map<String, Set<String>> matches,
            String[] cols) {
        String[][] result = new String[matches.size()][cols.length];
        // create an column order map
        Map<String, Integer> orders = new HashMap<String, Integer>();
        for (int i = 0; i < cols.length; i++)
            orders.put(cols[i], i);

        ArrayList<String> sortedIds = new ArrayList<String>(matches.keySet());
        Collections.sort(sortedIds);

        for (int i = 0; i < sortedIds.size(); i++) {
            String id = sortedIds.get(i);
            result[i][orders.get(COLUMN_GENE_ID)] = id;
            String fields = matches.get(id).toString();
            result[i][orders.get(COLUMN_DATASETS)] = fields.substring(1,
                    fields.length() - 1);
            result[i][orders.get(COLUMN_PROJECT_ID)] = this.projectId;
        }
        return result;
    }

    @Override
    protected String[] defineContextKeys() {
        return null;
    }
}