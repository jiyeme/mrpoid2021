/*
 * Copyright (C) 2013 The Mrpoid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mrpoid.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import com.edroid.common.utils.UIUtils;
import com.mrpoid.app.EmulatorActivity;
import com.edroid.common.utils.FileUtils;
import com.edroid.common.utils.Logger;
import com.mrpoid.mrplist.R;
import com.mrpoid.mrplist.utils.Test;


/**
 * 2012/10/9
 * 
 * @author Yichou
 * 
 */
public class Emulator implements Callback {
	public static final String TAG = "Emulator";
	static final Logger log = Logger.create(true, "Emulator");
	
	public static final String SDCARD_ROOT = Environment.getExternalStorageDirectory().getPath() + "/";
	public static final String DEF_WORK_PATH = "mythroad/";
	public static final String PUBLIC_STORAGE_PATH = SDCARD_ROOT + "Mrpoid/";

	/*SD卡最低可用容量*/
	public static final int DEF_MIN_SDCARD_SPACE_MB = 8;

	
	private static final int MSG_TIMER_OUT = 0x01,
		MSG_CALLBACK = 0x02,
		MSG_MR_SMS_GET_SC = 0x03,
        MSG_START_UP_MRP = 0x04,
        MSG_MRP_PAUSE = 0x05,
        MSG_MRP_RESUME = 0x06,
        MSG_MRP_STOP = 0x07,
        MSG_MRP_EXIT = 0x08,
        MSG_MRP_EVENT = 0x09,
        MSG_EXIT = 0x0a,
        MSG_INIT = 0x101,
        MSG_HEART = 0x200;

    private static boolean bSoLoaded;

    private EmuConfig cfg;

    private Context mContext;
    private EmuView emulatorView;
    private EmulatorActivity emulatorActivity;

    private HandlerThread mrpThread;
    private Handler handler;

    private EmuAudio audio;
    private EmuScreen screen;
    private String runMrpPath;
//    private Keypad mKeypad;
    private boolean running;
    private boolean bInited;
	private int procIndex;
	
	
	/**
	 * end with /
	 */
	private String mVmRoot = SDCARD_ROOT;
	private String mLastVmRoot = SDCARD_ROOT;
	
	/**
	 * end with /
	 */
	private String mWorkPath = DEF_WORK_PATH;
	private String mLastWorkPath = DEF_WORK_PATH;
	
	//--- native params below --------
	public int N2J_charW, N2J_charH; //这2个值保存 每次measure的结果，底层通过获取这2个值来获取尺寸
	public int N2J_memLen, N2J_memLeft, N2J_memTop;
	public String N2J_imei, N2J_imsi, N2J_sim;

	private int alive;

	static {
        System.loadLibrary("mrpoid");
		bSoLoaded = true;
    }

	public Emulator() {
	    mrpThread = new HandlerThread("mrp");
	    mrpThread.start();
	    handler = new Handler(mrpThread.getLooper(),this);
	    handler.sendEmptyMessageDelayed(MSG_HEART, 3000);
	}

    @Override
    public boolean handleMessage(Message msg) {
		Log.i(TAG, String.format("%X handleMessage: %s", msg.what, msg));
        switch (msg.what) {
			case MSG_HEART: {
				log.w("thread alive----------- " + alive++);
				handler.sendEmptyMessageDelayed(MSG_HEART, 30000);
				break;
			}

            case MSG_TIMER_OUT:
                native_event(MrDefines.MR_EMU_ON_TIMER, 0, 0);
                break;

            case MSG_CALLBACK:
                native_callback(msg.arg1, msg.arg2);
                break;

            case MSG_MR_SMS_GET_SC:
                native_event(MrDefines.MR_SMS_GET_SC, 0, 0); //获取不到，暂时返回都是0
                break;

            case MSG_START_UP_MRP:
                startup_i((String) msg.obj);
                break;
            case MSG_MRP_PAUSE:
                pause_i();
                break;
            case MSG_MRP_RESUME:
                resume_i();
                break;
            case MSG_MRP_STOP:
                stop_i();
                break;
            case MSG_MRP_EVENT:
                native_event((Integer) msg.obj, msg.arg1, msg.arg2); // 获取不到，暂时返回都是0
                break;
            case MSG_INIT:
                init_i();
                break;
            case MSG_EXIT:
                exit_i();
                break;

            default:
                return (1 == native_handleMessage(msg.what, msg.arg1, msg.arg2));
        }

        return true;
    }
	
	/**
	 * @param procIndex the procIndex to set
	 */
	public void setProcIndex(int procIndex) {
		this.procIndex = procIndex;
	}

	/**
	 * memory recycle when exit
	 */
	public void recycle() {
		native_destroy();
		audio.recyle();
		screen.recyle();
		bInited = false;
	}
	
	public void init(EmulatorActivity activity, EmuView view) {
        mContext = activity.getApplicationContext();
        emulatorActivity = activity;
        emulatorView = view;
        handler.sendEmptyMessage(MSG_INIT);
    }

    void init_i() {
		Log.i(TAG, "加载os库");
	    if(bInited)
	        return;

	    try {
//			System.loadLibrary("mrpoid");
			cfg = EmuConfig.getInstance();
			if(!bSoLoaded) {
				MrpoidSettings.useFullDsm = MrpoidSettings.getBooleanS(mContext, MrpoidSettings.kUseFullDsm, false);
				System.loadLibrary(MrpoidSettings.useFullDsm? "mrpoid2" : "mrpoid");
				bSoLoaded = true;
			}
		}catch (Throwable e){
			e.printStackTrace();
			Log.e(TAG, "库加载失败");
			UIUtils.toastMessage(getContext(), "库加载失败！");
			return;
		}
		
        screen = new EmuScreen(this);
        audio = new EmuAudio(this);

		// 模拟器路径初始化
		initPath();
		
		initFont();

        log.i("call native_create tid=" + Thread.currentThread().getId());

        native_init(screen, audio);

//		MrpoidSettings.getInstance().setNative();

		// 起线程获取短信中心
//		new Thread(new Runnable() {
//			@Override
//			public void run() {
//				String N2J_smsCenter = SmsUtil.getSmsCenter(mContext);
//				native_setStringOptions("smsCenter", N2J_smsCenter);
//				EmuLog.i(TAG, "smsCenter: " + N2J_smsCenter);
//			}
//		}).start();
        native_setStringOptions("smsCenter", "10086");

		EmuSmsManager.getDefault().attachContext(mContext);
		
		{
			ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
			List<RunningAppProcessInfo> list = am.getRunningAppProcesses();
			for(RunningAppProcessInfo info : list) {
				if(info.pid == Process.myPid()) {
					String name = info.processName;
					int i = name.lastIndexOf(':');
					if(i != -1)
						native_setStringOptions("procName", name.substring(i));
					break;
				}
			}
		}

		N2J_imsi = "000000000000000";
		N2J_imei = "000000000000000";

		bInited = true;
	}
	
	void initFont() {
		String fontFile = getVmFullPath() + "system/gb16_mrpoid.uc2";
		Log.i(TAG, "font path=" + fontFile);
		FileUtils.assetToFileIfNotExist(mContext, "fonts/gb16_mrpoid.uc2", new File(fontFile));
	}
	
	public boolean isInitialized() {
		return bInited;
	}
	
	public EmulatorActivity getActivity() {
		return emulatorActivity;
	}
	

	public EmuView getView() {
		return emulatorView;
	}
	
	public EmuScreen getScreen() {
		return screen;
	}
	
	public EmuAudio getAudio() {
		return audio;
	}
	
	public Context getContext() {
		return mContext;
	}
	
	public String getRunningMrpPath() {
		return runMrpPath;
	}

	public String getCurMrpAppName() {
		return native_getAppName(getVmFullPath() + runMrpPath);
	}
	
	public boolean isRunning() {
		return running;
	}
	
	/**
	 * after EmulatorSurface has created 启动 mrp 虚拟机
	 */
	public void startMrp(String path) {
		Log.i(TAG, "mrp 路径：" + path);
	    handler.obtainMessage(MSG_START_UP_MRP, path).sendToTarget();
    }

    void startup_i(String path) {
		if(running)
		    return;

        log.i("startUp: " + path);

        //1.是不是绝对路径
        if(path.startsWith(SDCARD_ROOT)) {
            final int i = path.indexOf(getVmWorkPath());
            if(i != -1) {
                int l = getVmWorkPath().length();
                path = path.substring(i + l);

                log.i("newPath=" + path);
            } else { //需要复制
                final int j = path.lastIndexOf(File.separatorChar);
                final String vpath = path.substring(j+1);

                File dstFile = getVmFullFilePath(vpath);

                log.i("copyMrp: " + path + " -> " + dstFile);

                if (FileUtils.FAILED == FileUtils.copyTo(dstFile, new File(path))) {
                    throw new RuntimeException("file path invalid, copy file to VmWorkPath fail!");
                }

                path = vpath;
            }
        }

        log.d("real path=" + path);

        this.runMrpPath = path;
		
		if(runMrpPath == null) {
			log.e("no run file!");
			stop();
			return;
		}
		
		log.i("start");
		
		//等所有环境准备好后再确定它为运行状态
        path = runMrpPath;
        if(path.charAt(0) != '*'){ //非固化应用
            path = "%" + runMrpPath;
        }

        running = true;

        Test.hello();
        // JNI启动MRP程序
        native_startMrp(path);
    }
	
	/**
	 * 停止 MRP 虚拟机
	 */
	public void stop() {
	    handler.sendEmptyMessage(MSG_MRP_STOP);
    }

	void stop_i() {
		log.i("stop");
		
		native_stop();
	}
	
	public void pause() {
        handler.sendEmptyMessage(MSG_MRP_PAUSE);
    }

	void pause_i() {
		audio.pause();
		screen.pause();
		native_pause();
	}
	
	public void resume() {
        handler.sendEmptyMessage(MSG_MRP_RESUME);
    }
	void resume_i() {
		audio.resume();
		screen.resume();
		native_resume();
	}
	
	/**
	 * 强制停止 MRP 虚拟机
	 */
	public void stopFoce() {
		stop();
	}

	void exit_i() {
		running = false; //什么时候设置好

		handler.removeCallbacksAndMessages(null);

		//下面这些都线程安全吗？
		N2J_timerStop();

		audio.stop();
		audio.recyle();
		screen.recyle();
		BitmapPool.recyle();

		emulatorActivity = null;
		emulatorView = null;

		Process.killProcess(Process.myPid());
	}

	public void onActivityDestroy() {
		handler.sendEmptyMessage(MSG_EXIT);
	}
	
	/**
	 * native vm 结束后 调用方法结束 java 层
	 * 
	 * 注：不能在这里杀掉进程，否则底层释放工作未完成
	 */
	private void N2J_finish(){
		log.d("N2J_finish() called");
		
		if(!running) return;

		if(emulatorActivity != null)
			emulatorActivity.finish();
	}

	public int handleSms(String num, String content) {
	    return MrDefines.MR_SUCCESS;
    }
	
	///////////////////////////////////////////////
	private void N2J_flush() {
		if(!running || emulatorView==null)
		    return;
		
		screen.postFlush();
	}
	
	public void N2J_timerStart(int t) {
//		System.out.println("N2J_timerStart " + t);

		if (!running)
			return;
		
		// 此法不可取
//		task = new TimerTask() {
//			@Override
//			public void run() {
//				handler.sendEmptyMessage(MSG_TIMER_OUT);
//			}
//		};
//		timer.schedule(task, t);

        handler.sendEmptyMessageDelayed(MSG_TIMER_OUT, t);
	}

	private void N2J_timerStop() {
		if(!running) return;
		
//		if(task != null){
//			task.cancel();
//			task = null;
//		}
//		timer.purge();
        handler.removeMessages(MSG_TIMER_OUT);
	}

	/**
	 * 调用 mr_event
	 * 
	 * @param p0
	 * @param p1
	 * @param p2
	 */
	public void postMrpEvent(int p0, int p1, int p2) {
		if(!running) return;

		handler.obtainMessage(MSG_MRP_EVENT, p1, p2, p0).sendToTarget();
//		vm_event(p0, p1, p2);
	}
	
	//////////// 编辑框接口  ////////////
	private String N2J_editInputContent;
	
	/// 底层访问方法 ////////////////////////////////////////
	public void setEditInputContent(String editInputContent) {
		this.N2J_editInputContent = editInputContent;
	}
	
	/**
	 * 底层调用显示编辑框
	 * 
	 * @param title 标题
	 * @param content 内容
	 * @param type 类型
	 * @param max 最大值
	 */
	private void N2J_showEdit(final String title, final String content, final int type, final int max) {
		emulatorActivity.showMrpInputer(title, content, type, max);
	}
	
	/**
	 * 底层获取一个 int 型参数
	 * 
	 * @param name 键值
	 * @return 参数值
	 */
	private int N2J_getIntSysinfo(String name) {
		//native 上调的函数一定要检查空指针，否则将导致致命错误
		if(name == null || mContext == null)
			return 0;
		
//		EmuLog.i(TAG, "getIntSysinfo("+name+")");
		
		if (name.equalsIgnoreCase("netType")) {
			return EmuUtils.getNetworkType(mContext);
		} else if (name.equalsIgnoreCase("netID")) {
			return EmuUtils.getNetworkID(mContext);
		}
		
		return 0;
	}
	
	/**
	 * 底层调用获取一个 String 类型的参数
	 * 
	 * @param name 键值
	 * @return 成功：返回获取到的参数 失败：返回 null
	 */
	private String N2J_getStringSysinfo(String name) {
		//native 上调的函数一定要检查空指针，否则将导致致命错误
		if(name == null || mContext == null)
			return null;
		
		if (name.equalsIgnoreCase("imei")) {
			TelephonyManager mTm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);  
            return N2J_imei;
		} else if (name.equalsIgnoreCase("imsi")) {
			TelephonyManager mTm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);  
			return N2J_imsi;
		} else if (name.equalsIgnoreCase("phone-model")) {
			return android.os.Build.MODEL; // 手机型号  
		} else if (name.equalsIgnoreCase("phone-num")) {
			TelephonyManager mTm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);  
			return "10086"; // 手机号码，有的可得，有的不可得
		}
		
		return null;
	}
	
	/**
	 * 底层请求显示一个对话框，该对话框弹出，表明底层出现了不可继续下去的错误，
	 * 需要退出 MRP 运行
	 * 
	 * @param msg
	 */
	private void N2J_showDlg(String msg){
		new AlertDialog.Builder(emulatorActivity) //注意这里不是用 Context 
				.setTitle(R.string.warn)
				.setMessage(msg)
				.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface arg0, int arg1) {
						stop(); //结束运行
					}
				})
				.create()
				.show();
	}
	
	/**
	 * 底层调用发送短信
	 * 
	 * @param num 号码
	 * @param content 内容
	 * @param showReport 显示发送报告
	 * @param showRet 显示发送结果
	 * 
	 * @return 0
	 */
	private int N2J_sendSms(String num, String content, boolean showReport, boolean showRet) {
		log.w("N2J_sendSms: " + num + ", " + content);
		
		postMrpEvent(MrDefines.MR_SMS_RESULT,  MrDefines.MR_SUCCESS, 0);
		
		//忽略发送短信
//		emulatorActivity.reqSendSms(msg, num);
		
		return 0;
	}
	
	/**
	 * 底层调用获取主机地址（有些手机貌似不行）
	 * 
	 * @param host 主机名
	 */
	private void N2J_getHostByName(final String host) {
		EmuLog.i(TAG, "N2J_getHostByName:" + host);
		
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					InetAddress[] addresses = InetAddress.getAllByName(host);
					if (addresses != null) {
						byte[] ba = addresses[0].getAddress();
						int ip = 0;
						for(int i=0; i<4; ++i){
							ip += (ba[i] & 0xff) << (8*(3-i));
						}
						N2J_requestCallback(0x1002, ip);
					}
				} catch (UnknownHostException e) {
					e.printStackTrace();
				}
			}
		}).start();
	}


	/**
	 * A Native Call
	 */
	public void N2J_requestCallback(int p0, int p1) {
		if(!running) return;

		log.i(String.format("N2J_requestCallback %d,%d pid=", p0, p1, Thread.currentThread().getId()));

		handler.obtainMessage(MSG_CALLBACK, p0, p1).sendToTarget();
	}
	
	/**
	 * 底层调用设置参数（int类型）
	 * 
	 * @param key 键值
	 * @param value 参数
	 */
	private void N2J_setOptions(String key, String value) {
		if(key == null)
		    return;
		
		log.i("N2J_setOptions(" + key + ", " + value + ")");
		
		if(key.equalsIgnoreCase("keepScreenOn")){
			if(emulatorView != null)
				emulatorView.setKeepScreenOn(Boolean.valueOf(value));
		}
	}
	
	/**
	 * 从 assets 读取tsf字体文件
	 * 
	 * @return 成功：字节数组 失败：null
	 */
	private byte[] N2J_readTsfFont() {
		InputStream is = null;
		byte[] buf = null;
		
		try {
			is = mContext.getAssets().open("fonts/font16.tsf");
			buf = new byte[is.available()];
			is.read(buf);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				is.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		return buf;
	}
	
	public void reqBrowser(String urlString) {
		if(!urlString.startsWith("http")) {
			urlString  = "http://" + urlString;
		}
		
		if(urlString.endsWith("wap.skmeg.com/dsmWap/error.jsp")) { //error page exit!
			stop();
		} else {
			Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			mContext.startActivity(intent);
		}
	}
	
	/**
	 * 底层调用万能方法
	 * 
	 * @param args
	 */
	private void N2J_callVoidMethod(String[] args) {
		if(null == args)
			return;
		
		int argc = args.length;
		if (argc > 0) {
			String action = args[0];
			
			if(action == null)
				return;
			
			if(action.equals("call")){
				if(argc >= 2 && args[1] != null){
					if(emulatorActivity!=null) emulatorActivity.reqCallPhone(args[1]);
				}
			} else if (action.equals("viewUrl")) {
				if(argc >= 2 && args[1] != null) {
					reqBrowser(args[1]);
				}
			} else if (action.equals("getSmsCenter")) { //获取短信中心，通过 mr_event 回调
				handler.sendEmptyMessageDelayed(MSG_MR_SMS_GET_SC, 500);
			} else if ("showToast".equals(action)) {
				Toast.makeText(mContext, args[1], Toast.LENGTH_SHORT).show();
			} else if ("crash".equals(action)) {
				new Thread() {
					@Override
					public void run() {
						Looper.prepare();
						Toast.makeText(mContext, "mrp线程异常退出！", Toast.LENGTH_LONG).show();
						Looper.loop();
					}
				}.start();
			}
		}
	}

	/**
	 * A Native Callback
	 */
	private void N2J_sendHandlerMessage(int what, int p0, int p1, int delay) {
		log.i(String.format("N2J_sendHandlerMessage %d %d %d %d", what, p0, p1, delay));
		handler.sendMessageDelayed(handler.obtainMessage(what, p0, p1), delay);
	}

	/**
	 * A Native Callback
	 */
	private void N2J_drawImage(String path, int x, int y, int w, int h) {
//		log.i("drawImage " + x)
		screen.drawBitmap(BitmapPool.getBitmap(path), x, y, w, h);
	}

	/**
	 * A Native Callback
	 */
	private Bitmap N2J_getBitmap(String path) {
		EmuLog.d(TAG, "N2J_getBitmap:" + path);
		
		return BitmapPool.getBitmap(path);
	}
	
	//-----------------------------------------------------------

	public void initPath() {
		if(!FileUtils.isSDAvailable(DEF_MIN_SDCARD_SPACE_MB)) {
//			MrpoidSettings.usePrivateDir = true;
//			Toast.makeText(context, "没有SD卡！", Toast.LENGTH_SHORT).show();
		}

        setVmRootPath(SDCARD_ROOT);

        setVmWorkPath(DEF_WORK_PATH + cfg.scnw + "x" + cfg.scnh + "/");

		log.i("sd path = " + mVmRoot);
		log.i("mythroad path = " + mWorkPath);
	}
	
	/**
	 * 获取默认运行根目录的完整路径
	 * 
	 * @return 绝对路径 /结尾
	 */
	public String getVmDefaultFullPath() {
		return (SDCARD_ROOT + DEF_WORK_PATH);
	}
	
	/**
	 * 获取运行根目录的完整路径
	 * 
	 * @return 绝对路径 /结尾
	 */
	public String getVmFullPath() {
		return (mVmRoot + mWorkPath);
	}
	
	/**
	 * 获取上一次成功改变，运行根目录的绝对路径
	 * 
	 * @return 绝对路径 /结尾
	 */
	public String getVmLastFullPath() {
		return (mLastVmRoot + mLastWorkPath);
	}
	
	/**
	 * 获取运行目录下的一个文件
	 * 
	 * @param name 文件名
	 * 
	 * @return
	 */
	public File getVmFullFilePath(String name) {
		return new File(mVmRoot + mWorkPath, name);
	}
	
	/**
	 * 获取公共目录下存储的文件
	 * 
	 * @param name
	 * @return
	 */
	public static File getPublicFilePath(String name){
		File file = new File(PUBLIC_STORAGE_PATH);
		FileUtils.createDir(file);
		return new File(PUBLIC_STORAGE_PATH, name);
	}
	
	/**
	 * SD卡根目录，该目录必须可以创建
	 * 
	 * @param tmp
	 */
	public void setVmRootPath(String tmp) {
		if (tmp == null || tmp.length() == 0) {
			log.e("setSDPath: null");
			return;
		}
		
		if(mVmRoot.equals(tmp))
			return;
		
		File path = new File(tmp);
		if (FileUtils.SUCCESS != FileUtils.createDir(path)) {
			log.e("setSDPath: " + path.getAbsolutePath() + " mkdirs FAIL!");
			return;
		}
		
		if(!path.canRead() || !path.canWrite()) {
			log.e("setSDPath: " + path.getAbsolutePath() + " can't read or write!");
			return;
		}
		
		int i = tmp.length();
		if(tmp.charAt(i-1) != '/'){
			tmp += '/';
		}
		
		mLastVmRoot = mVmRoot;
		mVmRoot = tmp;
		Emulator.native_setStringOptions("sdpath", mVmRoot);
		
		log.i("sd path has change to: " + mVmRoot);
	}
	
	public String getVmRootPath() {
		return mVmRoot;
	}
	
	/**
	 * 理论上 mythroad 路径可以为 "" 表示SD卡根目录，这里为了避免麻烦，还是让他不可以
	 * 
	 * @param tmp
	 */
	public void setVmWorkPath(String tmp) {
		if (tmp == null || tmp.length() == 0) {
			log.e("setMythroadPath: input error!");
			return;
		}
		
		if(mWorkPath.equals(tmp))
			return;
		
		File path = new File(mVmRoot, tmp);
		if (FileUtils.SUCCESS != FileUtils.createDir(path)) {
			log.e("setMythroadPath: " + path.getAbsolutePath() + " mkdirs FAIL!");
			return;
		}
		
		if(!path.canRead() || !path.canWrite()) {
			log.e("setMythroadPath: " + path.getAbsolutePath() + " can't read or write!");
			return;
		}

		int i = tmp.length();
		if (tmp.charAt(i - 1) != '/') {
			tmp += "/";
		}
		mLastWorkPath = mWorkPath;
		mWorkPath = tmp;
		Emulator.native_setStringOptions("mythroadPath", mWorkPath);

		log.i("mythroad path has change to: " + mWorkPath);
	}
	
	public String getVmWorkPath(){
		return mWorkPath;
	}
	
	private static final Emulator instance = new Emulator();
	public static Emulator getInstance(){
		return instance;
	}

	/// ////////////////////////////////////////
	public native void native_init(EmuScreen screen, EmuAudio audio);
    public native int native_startMrp(String path);
    public native void native_pause();
    public native void native_resume();
    public native void native_stop();
//    public native void native_timerOut();
    public native void native_event(int code, int p0, int p1);
    public native int native_smsRecv(String content, String num);
    public native void native_destroy();
    public native void native_getMemoryInfo();

    public native void native_callback(int what, int param);
    public native int native_handleMessage(int what, int p0, int p1);

    public native void native_screenReset(Bitmap a, Bitmap b, int w, int h);
    public native void native_lockBitmap();
    public native void native_unLockBitmap();

//    public native int vm_newSIMInd(int type, byte[] old_IMSI);
//    public native int vm_registerAPP(byte[] p, int len, int index);

    public static native String native_getStringOptions(String key);
    public static native void native_setStringOptions(String key, String value);
    public static native void native_setIntOptions(String key, int value);
    public static native String native_getAppName(String path);

	///////////////////////////////////

	public native void hello();
}
