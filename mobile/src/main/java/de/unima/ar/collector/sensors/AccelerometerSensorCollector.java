package de.unima.ar.collector.sensors;

import android.content.ContentValues;
import android.os.AsyncTask;
import android.hardware.Sensor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.unima.ar.collector.TCPClient;
import de.unima.ar.collector.SensorDataCollectorService;
import de.unima.ar.collector.controller.SQLDBController;
import de.unima.ar.collector.database.DatabaseHelper;
import de.unima.ar.collector.extended.Plotter;
import de.unima.ar.collector.shared.Settings;
import de.unima.ar.collector.shared.database.SQLTableName;
import de.unima.ar.collector.shared.util.DeviceID;
import de.unima.ar.collector.util.DBUtils;
import de.unima.ar.collector.util.PlotConfiguration;

/**
 * @author Fabian Kramm, Timo Sztyler, Nancy Kunath
 */
public class AccelerometerSensorCollector extends SensorCollector
{
    private static final int      type       = 1;
    private static final String[] valueNames = new String[]{ "attr_x", "attr_y", "attr_z", "attr_time" };
    private              float[]  gravity    = new float[]{ 0, 0, 0 };

    private static Map<String, Plotter>        plotters = new HashMap<>();
    private static Map<String, List<String[]>> cache    = new HashMap<>();

    private static TCPClient mTcpClient;


    public AccelerometerSensorCollector(Sensor sensor)
    {
        super(sensor);

        // create new plotter
        List<String> devices = DatabaseHelper.getStringResultSet("SELECT device FROM " + SQLTableName.DEVICES, null);
        for(String device : devices) {
            AccelerometerSensorCollector.createNewPlotter(device);
        }
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy)
    {
    }


    @Override
    public void SensorChanged(float[] values, long time)
    {
        float x = values[0];
        float y = values[1];
        float z = values[2];

        if(Settings.ACCLOWPASS) { // low pass filter
            final float alpha = (float) 0.8;
            gravity[0] = alpha * gravity[0] + (1 - alpha) * values[0];
            gravity[1] = alpha * gravity[1] + (1 - alpha) * values[1];
            gravity[2] = alpha * gravity[2] + (1 - alpha) * values[2];

            x = values[0] - gravity[0];
            y = values[1] - gravity[1];
            z = values[2] - gravity[2];
        }

        ContentValues newValues = new ContentValues();
        newValues.put(valueNames[0], x);
        newValues.put(valueNames[1], y);
        newValues.put(valueNames[2], z);
        newValues.put(valueNames[3], time);

        String deviceID = DeviceID.get(SensorDataCollectorService.getInstance());
        AccelerometerSensorCollector.writeSensorData(deviceID, newValues);
        AccelerometerSensorCollector.updateLivePlotter(deviceID, new float[]{ x, y, z });
    }


    @Override
    public Plotter getPlotter(String deviceID)
    {
        if(!plotters.containsKey(deviceID)) {
            AccelerometerSensorCollector.createNewPlotter(deviceID);
        }

        return plotters.get(deviceID);
    }


    @Override
    public int getType()
    {
        return type;
    }


    public static void createNewPlotter(String deviceID)
    {
        PlotConfiguration levelPlot = new PlotConfiguration();
        levelPlot.plotName = "LevelPlot";
        levelPlot.rangeMin = -10;
        levelPlot.rangeMax = 10;
        levelPlot.rangeName = "Speed";
        levelPlot.SeriesName = "Speed";
        levelPlot.domainName = "Axis";
        levelPlot.domainValueNames = Arrays.copyOfRange(valueNames, 0, 3);
        levelPlot.tableName = SQLTableName.ACCELEROMETER;
        levelPlot.sensorName = "Accelerometer";

        PlotConfiguration historyPlot = new PlotConfiguration();
        historyPlot.plotName = "HistoryPlot";
        historyPlot.rangeMin = -10;
        historyPlot.rangeMax = 10;
        historyPlot.domainMin = 0;
        historyPlot.domainMax = 80;
        historyPlot.rangeName = "Speed m/s";
        historyPlot.SeriesName = "Speed";
        historyPlot.domainName = "Time";
        historyPlot.seriesValueNames = Arrays.copyOfRange(valueNames, 0, 3);

        Plotter plotter = new Plotter(deviceID, levelPlot, historyPlot);
        plotters.put(deviceID, plotter);
    }


    public static void updateLivePlotter(String deviceID, float[] values)
    {
        Plotter plotter = plotters.get(deviceID);
        if(plotter == null) {   // this could be the case if the app was already running and a new, unknown devices established a connection
            AccelerometerSensorCollector.createNewPlotter(deviceID);
            plotter = plotters.get(deviceID);
        }

        plotter.setDynamicPlotData(values);
    }

    public static void writeSensorData(String deviceID, ContentValues newValues)
    {
        if(Settings.DATABASE_DIRECT_INSERT) {
            if(mTcpClient!=null && mTcpClient.getMRun() != false) {
                JSONObject ObJson = new JSONObject();
                try {
                    ObJson.put("deviceID",deviceID);
                    ObJson.put("sensorType","accelerometer");
                    JSONArray array = new JSONArray();
                    JSONObject values = new JSONObject();
                    values.put("timeStamp", newValues.getAsString("attr_time"));
                    values.put("x", newValues.getAsString("attr_x"));
                    values.put("y", newValues.getAsString("attr_y"));
                    values.put("z", newValues.getAsString("attr_z"));
                    array.put(values);
                    ObJson.put("data",array);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                mTcpClient.sendMessage(ObJson.toString());
            }
            return;
        } else {
            List<String[]> clone = DBUtils.manageCache(deviceID, cache, newValues, (Settings.DATABASE_CACHE_SIZE + type * 2));
            if(clone != null) {
                JSONObject ObJson = new JSONObject();
                try {
                    ObJson.put("deviceID",deviceID);
                    ObJson.put("sensorType","accelerometer");
                    JSONArray array = new JSONArray();
                    JSONObject values = new JSONObject();
                    for (int i=0; i<clone.size(); i++) {
                        values.put("timeStamp", clone.get(i)[0].toString());
                        values.put("x", clone.get(i)[1].toString());
                        values.put("y", clone.get(i)[2].toString());
                        values.put("z", clone.get(i)[3].toString());
                        array.put(values);
                    }
                    ObJson.put("data",array);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                if(mTcpClient!=null && mTcpClient.getMRun() != false) {
                    mTcpClient.sendMessage(ObJson.toString());
                }
            }
        }

    }

    public static void flushDBCache(String deviceID)
    {

    }

    public static void openSocket(String deviceID){
        // connect to the server
        ConnectTask task = new ConnectTask();
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public static void closeSocket(String deviceID){
        // disconnect from the server
        //mTcpClient.stopClient(deviceID + " Accelerometer: ");
        mTcpClient.deregister();
    }

    private static class ConnectTask extends AsyncTask<String,String,TCPClient> {

        @Override
        protected TCPClient doInBackground(String... message) {

            mTcpClient = TCPClient.getInstance();
            mTcpClient.register();

            return null;
        }

    }
}