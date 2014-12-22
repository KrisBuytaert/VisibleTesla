/*
 * RestStore.java - Copyright(c) 2013, 2014 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: Oct 23, 2014
 */
package org.noroomattheinn.visibletesla.cycles;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Date;
import jxl.write.WritableSheet;
import jxl.write.WriteException;
import org.apache.commons.lang3.StringUtils;
import static org.noroomattheinn.tesla.Tesla.logger;
import org.noroomattheinn.timeseries.Row;
import org.noroomattheinn.timeseries.TimeSeries.RowCollector;
import org.noroomattheinn.visibletesla.AppContext;
import org.noroomattheinn.visibletesla.Prefs;

/**
 * ChargeStore: Manage persistent storage for Charge Cycle information.
 * 
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
public class RestStore extends CycleStore<RestCycle> {
/*------------------------------------------------------------------------------
 *
 * Internal  State
 * 
 *----------------------------------------------------------------------------*/
    
    private boolean flushOnEachWrite;
    private final RestCycleExporter exporter;
    
/*==============================================================================
 * -------                                                               -------
 * -------              Public Interface To This Class                   ------- 
 * -------                                                               -------
 *============================================================================*/
    
    public RestStore(AppContext appContext) throws FileNotFoundException {
        super(appContext, "rest", RestCycle.class);
        this.flushOnEachWrite = true;
        this.exporter = new RestCycleExporter(ac);
                
        ac.lastRestCycle.addTracker(false, new Runnable() {
            @Override public void run() {
                cycleWriter.println(ac.lastRestCycle.get().toJSONString());
                exporter.submitData(ac.lastRestCycle.get());
                if (flushOnEachWrite) cycleWriter.flush();
            }
        });
    }
   
    public void export() { exporter.export(this); }
    
    
/*------------------------------------------------------------------------------
 *
 * Methods related to creating the RestStore the first time. If necessary, we
 * paw through old data from the StatsCollector to initialize the RestStore.
 * We do this at most once in the lifetime of an installation of VT
 * 
 *----------------------------------------------------------------------------*/
    
    public static boolean requiresInitialLoad(AppContext ac) {
        File f = new File(ac.appFileFolder(), ac.vehicle.getVIN()+".rest.json");
        return !f.exists();
    }
    
    public void doIntialLoad() {
        // Create a rest file based on existing data. This is a one time thing.
        logger.info("Synthesizing RestCycle data - one time only");
        final RestMonitor rm = new RestMonitor(ac);
        try {
            flushOnEachWrite = false;
            ac.statsCollector.getFullTimeSeries().streamRows(null, new RowCollector() {
                @Override public boolean collect(Row r) {
                    rm.handleNewData(r);
                    return true;
                }
            });
        } catch (Exception e) {
            logger.warning("Error during intial load of Rest Cycles: " + e);
        }
        flushOnEachWrite = true;
        cycleWriter.flush();
    }
    
}


/*------------------------------------------------------------------------------
 *
 * The CycleExporter for Charge Cycles
 * 
 *----------------------------------------------------------------------------*/

class RestCycleExporter extends CycleExporter<RestCycle> {
    private static final String[] labels = {
            "Start Date/Time", "Ending Date/Time", "Start Range", "End Range",
            "Start SOC", "End SOC", "(Latitude, ", " Longitude)", "Loss/Hr"};

    RestCycleExporter(AppContext appContext) {
        super(appContext, "Rest", labels, Prefs.get().submitAnonRest);
    }
    
    @Override protected void emitRow(
            WritableSheet sheet, int row, RestCycle cycle, StandardFormats sf)
            throws WriteException {
        int column = 0;
        sheet.addCell(new jxl.write.DateTime(column++, row, new Date(cycle.startTime), sf.dateFormat));
        sheet.addCell(new jxl.write.DateTime(column++, row, new Date(cycle.endTime), sf.dateFormat));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.startRange, sf.standardFormat));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.endRange, sf.standardFormat));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.startSOC, sf.standardFormat));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.endSOC, sf.standardFormat));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.lat, sf.standardFormat));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.lng, sf.standardFormat));
        String lossFormula =  String.format("(C%1$d-D%1$d)/((B%1$d-A%1$d)*24)", row+1);
        jxl.write.Formula f = new jxl.write.Formula(column, row, lossFormula, sf.standardFormat);
        sheet.addCell(f);
    }
    
    @Override protected String filterSubmissionData(String jsonRep) {
        // Strip the closing curly to prepare to add more fields
        jsonRep = StringUtils.substringBefore(jsonRep, "}");
        // Concatenate the extra fields and put back the closing curly
        return String.format("%s, \"uuid\": \"%s\" }", jsonRep, ac.vehicle.getUUID());
    }
}
