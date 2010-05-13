package org.apidb.apicomplexa.wsfplugin.spanlogic;

import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.gusdb.wdk.controller.CConstants;
import org.gusdb.wdk.model.AnswerValue;
import org.gusdb.wdk.model.WdkModel;
import org.gusdb.wdk.model.WdkModelException;
import org.gusdb.wdk.model.WdkUserException;
import org.gusdb.wdk.model.dbms.SqlUtils;
import org.gusdb.wdk.model.jspwrap.WdkModelBean;
import org.gusdb.wdk.model.query.ProcessQueryInstance;
import org.gusdb.wdk.model.user.Step;
import org.gusdb.wdk.model.user.User;
import org.gusdb.wsf.plugin.AbstractPlugin;
import org.gusdb.wsf.plugin.WsfRequest;
import org.gusdb.wsf.plugin.WsfResponse;
import org.gusdb.wsf.plugin.WsfServiceException;
import org.json.JSONException;

public class SpanCompositionPlugin extends AbstractPlugin {

    public static String COLUMN_SOURCE_ID = "source_id";
    public static String COLUMN_PROJECT_ID = "project_id";
    public static String COLUMN_WDK_WEIGHT = "wdk_weight";

    public static String PARAM_OPERATION = "span_operation";
    public static String PARAM_OUTPUT = "span_output";
    public static String PARAM_SPAN = "span_";
    public static String PARAM_SPAN_BEGIN = "span_begin_";
    public static String PARAM_SPAN_BEGIN_DIRECTION = "span_begin_direction_";
    public static String PARAM_SPAN_BEGIN_OFFSET = "span_begin_offset_";
    public static String PARAM_SPAN_END = "span_end_";
    public static String PARAM_SPAN_END_DIRECTION = "span_end_direction_";
    public static String PARAM_SPAN_END_OFFSET = "span_end_offset_";

    public static String PARAM_VALUE_OVERLAP = "overlap";
    public static String PARAM_VALUE_A_CONTAIN_B = "a_contain_b";
    public static String PARAM_VALUE_B_CONTAIN_A = "b_contain_a";

    public static String PARAM_VALUE_START = "start";
    public static String PARAM_VALUE_STOP = "stop";

    public static String PARAM_VALUE_OUTPUT_A = "a";
    public static String PARAM_VALUE_OUTPUT_B = "b";

    public static String PARAM_VALUE_UPSTREAM = "+";
    public static String PARAM_VALUE_DOWNSTREAM = "-";

    public String[] getColumns() {
        return new String[] { COLUMN_PROJECT_ID, COLUMN_SOURCE_ID,
                COLUMN_WDK_WEIGHT };
    }

    public String[] getRequiredParameterNames() {
        return new String[] { PARAM_OPERATION, PARAM_SPAN + "a",
                PARAM_SPAN + "b" };
    }

    public void validateParameters(WsfRequest request)
            throws WsfServiceException {
    // do nothing
    }

    public WsfResponse execute(WsfRequest request) throws WsfServiceException {
        Map<String, String> params = request.getParams();
        String operation = params.get(PARAM_OPERATION);
        String output = params.get(PARAM_OUTPUT);
        if (output == null || !output.equalsIgnoreCase(PARAM_VALUE_OUTPUT_B))
            output = PARAM_VALUE_OUTPUT_A;

        // get the proper begin & end of the derived regions.
        String[] startStopA = getStartStop(params, "a");
        String[] startStopB = getStartStop(params, "b");

        WdkModelBean wdkModelBean = (WdkModelBean) this.context.get(CConstants.WDK_MODEL_KEY);
        WdkModel wdkModel = wdkModelBean.getModel();

        Map<String, String> context = request.getContext();
        String userSignature = context.get(ProcessQueryInstance.CTX_USER);
        try {
            // get the sql to the cache table that represents user's two input
            // operands.
            String cacheA = getCache(wdkModel, userSignature, params, "a");
            String cacheB = getCache(wdkModel, userSignature, params, "b");

            // compose the final sql by comparing two regions with span
            // operation.
            String sql = composeSql(request.getProjectId(), operation, cacheA,
                    cacheB, startStopA, startStopB, output);

            logger.debug("SPAN LOGIC SQL:\n" + sql);

            // execute the final sql, and fetch the result for the output.
            return getResult(wdkModel, sql, request.getOrderedColumns());
        } catch (Exception ex) {
            throw new WsfServiceException(ex);
        }
    }

    private String getCache(WdkModel wdkModel, String userSignature,
            Map<String, String> params, String suffix)
            throws NoSuchAlgorithmException, WdkUserException,
            WdkModelException, SQLException, JSONException {
        User user;
        if (userSignature == null) {
            user = wdkModel.getSystemUser();
        } else {
            user = wdkModel.getUserFactory().getUser(userSignature);
        }
        int stepId = Integer.valueOf(params.get(PARAM_SPAN + suffix));
        Step step = user.getStep(stepId);
        AnswerValue answerValue = step.getAnswerValue();
        return answerValue.getIdSql();
    }

    private String[] getStartStop(Map<String, String> params, String suffix) {
        // get the user's choice of begin & end, and the offsets from params.
        String begin = params.get(PARAM_SPAN_BEGIN + suffix);
        if (begin == null) begin = PARAM_VALUE_START;
        String end = params.get(PARAM_SPAN_END + suffix);
        if (end == null) end = PARAM_VALUE_STOP;

        String beginDir = params.get(PARAM_SPAN_BEGIN_DIRECTION + suffix);
        if (beginDir == null) beginDir = PARAM_VALUE_UPSTREAM;
        String endDir = params.get(PARAM_SPAN_END_DIRECTION + suffix);
        if (endDir == null) endDir = PARAM_VALUE_UPSTREAM;

        int beginOff = 0;
        if (params.containsKey(PARAM_SPAN_BEGIN_OFFSET + suffix))
            beginOff = Integer.valueOf(params.get(PARAM_SPAN_BEGIN_OFFSET
                    + suffix));
        int endOff = 0;
        if (params.containsKey(PARAM_SPAN_END_OFFSET + suffix))
            endOff = Integer.valueOf(params.get(PARAM_SPAN_END_OFFSET + suffix));

        if (beginDir.equals(PARAM_VALUE_UPSTREAM)) beginOff *= -1;
        if (endDir.equals(PARAM_VALUE_UPSTREAM)) endOff *= -1;

        String table = "fl" + suffix + ".";

        // depending on whether the feature is on forward or reversed strand,
        // and user's choice of begin and end, we get the proper begin of the
        // region.
        StringBuilder builder = new StringBuilder("(CASE ");
        builder.append("WHEN " + table + "is_reversed = 0 ");
        builder.append(" AND '" + begin + "' = 'start' ");
        builder.append("THEN (" + table + "start_min + 1*(" + beginOff + ")) ");
        builder.append("WHEN " + table + "is_reversed = 0 ");
        builder.append(" AND '" + begin + "' = 'stop' ");
        builder.append("THEN (" + table + "end_max + 1*(" + beginOff + ")) ");
        builder.append("WHEN " + table + "is_reversed = 1 ");
        builder.append(" AND '" + end + "' = 'start' ");
        builder.append("THEN (" + table + "end_max - 1*(" + endOff + ")) ");
        builder.append("ELSE (" + table + "start_min - 1*(" + endOff + ")) ");
        builder.append("END)");
        String start = builder.toString();

        // we get the proper end of the region.
        builder = new StringBuilder("(CASE ");
        builder.append("WHEN " + table + "is_reversed = 0 ");
        builder.append(" AND '" + end + "' = 'start' ");
        builder.append("THEN (" + table + "start_min + 1*(" + endOff + ")) ");
        builder.append("WHEN " + table + "is_reversed = 0 ");
        builder.append(" AND '" + end + "' = 'stop' ");
        builder.append("THEN (" + table + "end_max + 1*(" + endOff + ")) ");
        builder.append("WHEN " + table + "is_reversed = 1 ");
        builder.append(" AND '" + begin + "' = 'start' ");
        builder.append("THEN (" + table + "end_max - 1*(" + beginOff + ")) ");
        builder.append("ELSE (" + table + "start_min - 1*(" + beginOff + ")) ");
        builder.append("END)");
        String stop = builder.toString();

        return new String[] { start, stop };
    }

    private String composeSql(String projectId, String operation, String sqlA,
            String sqlB, String[] regionA, String[] regionB, String output) {
        StringBuilder builder = new StringBuilder("SELECT ");
        
        // determine the output type
        builder.append(" DISTINCT f" + output + ".* FROM ");
        
        // construct the first sub-sql for region A.
        builder.append("(SELECT fla.feature_source_id AS source_id, ");
        builder.append("   fla.na_sequence_id, ca.project_id, ca.wdk_weight, ");
        builder.append(regionA[0] + " AS begin, " + regionA[1] + " AS end");
        builder.append(" FROM apidb.FEATURELOCATION fla, " + sqlA + " ca ");
        builder.append(" WHERE fla.feature_source_id = ca.source_id ");
        builder.append("   AND fla.is_top_level = 1) fa, ");
        
        // construct the second sub-sql for region B.
        builder.append("(SELECT flb.feature_source_id AS source_id, ");
        builder.append("   flb.na_sequence_id, cb.project_id, cb.wdk_weight, ");
        builder.append(regionB[0] + " AS begin, " + regionB[1] + " AS end");
        builder.append(" FROM apidb.FEATURELOCATION flb, " + sqlB + " cb ");
        builder.append(" WHERE flb.feature_source_id = cb.source_id ");
        builder.append("   AND flb.is_top_level = 1) fb ");
        
        // make sure the regions come from sequence source.
        builder.append(" WHERE fa.na_sequence_id = fb.na_sequence_id ");
        
        // restrict the regions to have start_min <= end_max
        builder.append("   AND fa.begin <= fa.end ");
        builder.append("   AND fb.begin <= fb.end ");
        
        // apply span operation.
        if (operation.equals(PARAM_VALUE_OVERLAP)) {
            builder.append("  AND fa.begin <= fb.end ");
            builder.append("  AND fa.end >= fb.begin ");
        } else if (operation.equals(PARAM_VALUE_A_CONTAIN_B)) {
            builder.append("  AND fa.begin <= fb.begin ");
            builder.append("  AND fa.end >= fb.end ");
        } else { // b_contain_a
            builder.append("  AND fa.begin >= fb.begin ");
            builder.append("  AND fa.end <= fb.end ");
        }

        return builder.toString();
    }

    private WsfResponse getResult(WdkModel wdkModel, String sql,
            String[] orderedColumns) throws WdkUserException,
            WdkModelException, SQLException {
        DataSource dataSource = wdkModel.getQueryPlatform().getDataSource();
        ResultSet results = null;
        try {
            results = SqlUtils.executeQuery(wdkModel, dataSource, sql);
            List<String[]> records = new ArrayList<String[]>();
            while (results.next()) {
                String[] record = new String[orderedColumns.length];
                for (int i = 0; i < record.length; i++) {
                    record[i] = results.getObject(orderedColumns[i]).toString();
                }
                records.add(record);
            }

            // now copy the records into an array
            String[][] array = new String[records.size()][orderedColumns.length];
            for (int i = 0; i < array.length; i++) {
                String[] record = records.get(i);
                System.arraycopy(record, 0, array[i], 0, record.length);
            }

            WsfResponse wsfResult = new WsfResponse();
            wsfResult.setResult(array);
            return wsfResult;
        } finally {
            SqlUtils.closeResultSet(results);
        }
    }

    @Override
    protected String[] defineContextKeys() {
        return new String[] { CConstants.WDK_MODEL_KEY };
    }
}