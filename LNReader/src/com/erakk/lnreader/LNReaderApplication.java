package com.erakk.lnreader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.erakk.lnreader.activity.DownloadListActivity;
import com.erakk.lnreader.callback.ICallbackNotifier;
import com.erakk.lnreader.dao.NovelsDao;
import com.erakk.lnreader.model.DownloadModel;
import com.erakk.lnreader.service.UpdateService;

/*
 * http://www.devahead.com/blog/2011/06/extending-the-android-application-class-and-dealing-with-singleton/
 */
public class LNReaderApplication extends Application {
	private static final String TAG = LNReaderApplication.class.toString();
	private static NovelsDao novelsDao = null;
	private static DownloadListActivity downloadListActivity = null;
	private static UpdateService service = null;
	private static LNReaderApplication instance;
	private static Hashtable<String, AsyncTask<?, ?, ?>> runningTasks;
	private static ArrayList<DownloadModel> downloadList;
	
	private static Object lock = new Object();
	
	@Override
	public void onCreate()
	{
		super.onCreate();

		// Initialise the singletons so their instances
		// are bound to the application process.
		initSingletons();
		instance = this;
		
		doBindService();
		Log.d(TAG, "Application created.");
	}
	
	public static LNReaderApplication getInstance() {
		return instance;
	}
	
	protected void initSingletons()
	{
		if(novelsDao == null) novelsDao = NovelsDao.getInstance(this);
		if(downloadListActivity == null) downloadListActivity = DownloadListActivity.getInstance();
		if(runningTasks == null) runningTasks = new Hashtable<String, AsyncTask<?, ?, ?>>();
		if(downloadList == null) downloadList = new ArrayList<DownloadModel>();
	}
	
	/*
	 * AsyncTask listing method
	 */
	public static Hashtable<String, AsyncTask<?, ?, ?>> getTaskList() {
		return runningTasks;
	}
	
	public AsyncTask<?, ?, ?> getTask(String key) {
		return runningTasks.get(key);
	}
	
	
	public boolean addTask(String key, AsyncTask<?, ?, ?> task) {
		synchronized (lock) {
			if(runningTasks.containsKey(key)) {
				AsyncTask<?, ?, ?> tempTask = runningTasks.get(key);
				if(tempTask != null && tempTask.getStatus() != Status.FINISHED) return false;
			}
			runningTasks.put(key, task);
			return true;
		}
	}
	
	public boolean removeTask(String key) {
		synchronized (lock) {
			if(!runningTasks.containsKey(key)) return false;
			runningTasks.remove(key);
			return true;
		}
	}
	public boolean isOnline() {
	    ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
	    NetworkInfo netInfo = cm.getActiveNetworkInfo();
	    if (netInfo != null && netInfo.isConnectedOrConnecting()) {
	        return true;
	    }
	    return false;
	}
	
	/*
	 * DownloadActivity method
	 */
	public int addDownload(String id, String name){
		synchronized (lock) {
			downloadList.add(new DownloadModel(id, name, 0));
			if (DownloadListActivity.getInstance() != null)
				DownloadListActivity.getInstance().updateContent();
			return downloadList.size();
		}
	}
	
	public void removeDownload(String id){
		synchronized (lock) {
			for (int i=0;i<downloadList.size();i++) {
				if (downloadList.get(i).getDownloadId() == id) {
					downloadList.remove(i);
				}
			}
		}
		if (DownloadListActivity.getInstance() != null)
			DownloadListActivity.getInstance().updateContent();
	}
	
	public String getDownloadDescription(String id){
		String name = "";
		for (int i=0;i<downloadList.size();i++) {
			if (downloadList.get(i).getDownloadId() == id) {
				name = downloadList.get(i).getDownloadName();
			}
		}
		return name;
	}
	
	public boolean checkIfDownloadExists(String name) {
		synchronized (lock) {
			boolean exists = false;
			for (int i=0;i<downloadList.size();i++) {
				if (downloadList.get(i).getDownloadName().equals(name)) {
					exists = true;
				}
			}
			return exists;
		}
	}
	
	public ArrayList<DownloadModel> getDownloadList() {
		return downloadList;
	}
	
//	public void updateDownload(String id, Integer progress){
//		
//		for (int i=0;i<downloadList.size();i++) {
//			if (downloadList.get(i).getDownloadId() == id) {
//				downloadList.get(i).setDownloadProgress(progress);
//			}
//		}
//		if (DownloadListActivity.getInstance() != null)
//			DownloadListActivity.getInstance().updateContent();
//	}
	
	
	public void updateDownload(String id, Integer progress, String message){
		
		/*
		 * Although this may seem an attempt at a fake incremental download bar
		 * its actually a progressbar smoother.
		 */
		
		int index = 0;
		Integer oldProgress;
		final Integer Increment;
		int smoothTime = 1000;
		int tickTime = 25;
		int tempIncrease = 0;
		for (int i=0;i<downloadList.size();i++) {
			if (downloadList.get(i).getDownloadId() == id) {
				index = i;
			}
		}
		final int idx = index;

		// Download status message 
		if (downloadList.get(idx) != null) {
			downloadList.get(idx).setDownloadMessage(message);
		}
		if (DownloadListActivity.getInstance() != null) {
			DownloadListActivity.getInstance().updateContent();
		}
		
		oldProgress = downloadList.get(index).getDownloadProgress();
		tempIncrease = (progress-oldProgress);
		if (tempIncrease < smoothTime/tickTime) {
			smoothTime = tickTime*tempIncrease;
			tempIncrease = 1;
		}
		else
			tempIncrease/=(smoothTime/tickTime);
		
		Increment = tempIncrease;
		new CountDownTimer(smoothTime, tickTime) {
			public void onTick(long millisUntilFinished) {
				if(downloadList.size() > idx) {
					DownloadModel temp = downloadList.get(idx);
					if (temp != null) {
						temp.setDownloadProgress(temp.getDownloadProgress() + Increment);
					}
				}
				if (DownloadListActivity.getInstance() != null) {
					DownloadListActivity.getInstance().updateContent();
				}
			}

			public void onFinish() {
			}
		}.start();
	}
	
	/*
	 * UpdateService method
	 */
	private ServiceConnection mConnection = new ServiceConnection() {

	    public void onServiceConnected(ComponentName className, IBinder binder) {
	    	service = ((UpdateService.MyBinder) binder).getService();
			Log.d(UpdateService.TAG, "onServiceConnected");
	      	Toast.makeText(getApplicationContext(), "Connected", Toast.LENGTH_SHORT).show();
	    }

	    public void onServiceDisconnected(ComponentName className) {
	    	service = null;
			Log.d(UpdateService.TAG, "onServiceDisconnected");
	   	}
	};
	
	private void doBindService() {
	    bindService(new Intent(this, UpdateService.class), mConnection, Context.BIND_AUTO_CREATE);
		Log.d(UpdateService.TAG, "doBindService");
	}
	
	public void setUpdateServiceListener(ICallbackNotifier notifier){
		if(service != null){
			service.notifier = notifier;
		}
	}
	
	public void runUpdateService(boolean force, ICallbackNotifier notifier) {
		if(service == null){
			doBindService();
			service.force = force;
			service.notifier = notifier;
		}
		else
			service.force = force;
			service.notifier = notifier;
			service.onStartCommand(null, BIND_AUTO_CREATE, (int)(new Date().getTime()/1000));
	}
	
	@Override
	public void onLowMemory () {
		
		/*
		 * java.lang.IllegalArgumentException
		 * in android.app.LoadedApk.forgetServiceDispatcher
		 * 
		 * probable crash: service is not checked if it exists after onLowMemory.
		 * Technically fixed. needs checking.
		 */
		if (mConnection != null) {
			Log.w(TAG, "Low Memory, unbind service...");
			unbindService(mConnection);
		}
		super.onLowMemory();		
	}
	
	/*
	 * CSS caching method.
	 * Also used for caching javascript.
	 */
	private Hashtable<Integer, String> cssCache = null;
	public String ReadCss(int styleId) {
		if(cssCache == null)
			cssCache = new Hashtable<Integer, String>();
		if(!cssCache.containsKey(styleId)) {
			StringBuilder contents = new StringBuilder();
			InputStream in =  getApplicationContext().getResources().openRawResource(styleId);
			InputStreamReader isr = new InputStreamReader(in);
			BufferedReader buff = new BufferedReader(isr);
			String temp = null;
			try {
				while((temp = buff.readLine()) != null){
					contents.append(temp);
				}
				buff.close();
				isr.close();
				in.close();
			} catch (IOException e) {
				Log.e(TAG, "Error reading asset: " + e.getMessage(), e);
			}
			cssCache.put(styleId, contents.toString());
		}
		return cssCache.get(styleId);
	}
	
	public void resetFirstRun() {
		SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(this).edit();
	    edit.remove(Constants.PREF_FIRST_RUN);
	    edit.commit();
	}
}
