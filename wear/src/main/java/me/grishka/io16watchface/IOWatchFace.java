package me.grishka.io16watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class IOWatchFace extends CanvasWatchFaceService{

	private float[][] paths;

	/**
	 * Update rate in milliseconds for interactive mode. We update once a second since seconds are
	 * displayed in interactive mode.
	 */
	private static final long INTERACTIVE_UPDATE_RATE_MS=1000;

	/**
	 * Handler message id for updating the time periodically in interactive mode.
	 */
	private static final int MSG_UPDATE_TIME=0;

	@Override
	public Engine onCreateEngine(){
		return new Engine();
	}

	private static class EngineHandler extends Handler{
		private final WeakReference<IOWatchFace.Engine> mWeakReference;

		public EngineHandler(IOWatchFace.Engine reference){
			mWeakReference=new WeakReference<>(reference);
		}

		@Override
		public void handleMessage(Message msg){
			IOWatchFace.Engine engine=mWeakReference.get();
			if(engine!=null){
				switch(msg.what){
					case MSG_UPDATE_TIME:
						engine.handleUpdateTimeMessage();
						break;
				}
			}
		}
	}

	private class Engine extends CanvasWatchFaceService.Engine{
		final Handler mUpdateTimeHandler=new EngineHandler(this);
		boolean mRegisteredTimeZoneReceiver=false;
		Paint mBackgroundPaint;
		Paint paint;
		boolean mAmbient;
		Time time;
		final BroadcastReceiver mTimeZoneReceiver=new BroadcastReceiver(){
			@Override
			public void onReceive(Context context, Intent intent){
				time.clear(intent.getStringExtra("time-zone"));
				time.setToNow();
			}
		};

		private float digitSize;
		private float[] tmpDigit=new float[DigitPaths.PATHS[0].length];
		private AnticipateOvershootInterpolator interpolator=new AnticipateOvershootInterpolator(1f);
		private DecelerateInterpolator decelerate=new DecelerateInterpolator();
		private int[] prevDigits={0, 0, 0, 0, 0, 0};
		private int[] animFromDigits={0, 0, 0, 0, 0, 0};
		private long[] animStartTimes={0, 0, 0, 0, 0, 0};
		private long[] colorAnimStartTimes={0, 0, 0, 0, 0, 0};
		private boolean[] animatingColorOut={false, false, false, false, false, false};
		private RectF[] digitBounds={new RectF(), new RectF(), new RectF(), new RectF()};
		private Bitmap[] cachedDigits;
		private long ambientExitTime;
		private boolean justExitedAmbient;

		//private final int[] COLORS={0xFF78909c, 0xFFef5350, 0xFF5C6BC0, 0xFF26C6DA, 0xFF8cf2f2};
		private final int[] COLORS={0xFFFFFFFF, 0xFFef5350, 0xFF5C6BC0, 0xFF26C6DA, 0xFF8cf2f2};

		/**
		 * Whether the display supports fewer bits for each color in ambient mode. When true, we
		 * disable anti-aliasing in ambient mode.
		 */
		boolean mLowBitAmbient;

		@Override
		public void onCreate(SurfaceHolder holder){
			super.onCreate(holder);

			setWatchFaceStyle(new WatchFaceStyle.Builder(IOWatchFace.this)
					.setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
					.setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
					.setHotwordIndicatorGravity(Gravity.TOP | Gravity.RIGHT)
					.setShowSystemUiTime(false)
					.setAcceptsTapEvents(true)
					.build());
			Resources resources=IOWatchFace.this.getResources();

			mBackgroundPaint=new Paint();
			//mBackgroundPaint.setColor(0xFFFFFFFF);
			mBackgroundPaint.setColor(0xFF000000);


			paint=new Paint(Paint.ANTI_ALIAS_FLAG);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(1f*getResources().getDisplayMetrics().density);
			paint.setColor(0xFFFFFFFF);
			//paint.setStrokeCap(Paint.Cap.ROUND);

			time=new Time();
		}

		@Override
		public void onDestroy(){
			mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
			super.onDestroy();
		}

		@Override
		public void onVisibilityChanged(boolean visible){
			super.onVisibilityChanged(visible);

			if(visible){
				registerReceiver();

				// Update time zone in case it changed while we weren't visible.
				time.clear(TimeZone.getDefault().getID());
				time.setToNow();
			}else{
				unregisterReceiver();
			}

			// Whether the timer should be running depends on whether we're visible (as well as
			// whether we're in ambient mode), so we may need to start or stop the timer.
			updateTimer();
		}

		private void registerReceiver(){
			if(mRegisteredTimeZoneReceiver){
				return;
			}
			mRegisteredTimeZoneReceiver=true;
			IntentFilter filter=new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
			IOWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
		}

		private void unregisterReceiver(){
			if(!mRegisteredTimeZoneReceiver){
				return;
			}
			mRegisteredTimeZoneReceiver=false;
			IOWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
		}

		@Override
		public void onApplyWindowInsets(WindowInsets insets){
			super.onApplyWindowInsets(insets);

			boolean isRound=insets.isRound();

			digitSize=(getResources().getDisplayMetrics().widthPixels)/(isRound ? 10.5f : 9.5f);

			paths=new float[10][];
			cachedDigits=new Bitmap[10];
			paint.setColor(0xFFFFFFFF);
			for(int i=0;i<10;i++){
				paths[i]=new float[DigitPaths.PATHS[i].length];
				for(int j=0; j<paths[i].length; j++){
					paths[i][j]=DigitPaths.PATHS[i][j]*digitSize;
				}
				cachedDigits[i]=Bitmap.createBitmap(Math.round(digitSize*2), Math.round(digitSize*2), Bitmap.Config.ALPHA_8);
				Canvas canvas=new Canvas(cachedDigits[i]);
				canvas.translate(digitSize, digitSize);
				canvas.drawLines(paths[i], paint);
			}

		}

		@Override
		public void onPropertiesChanged(Bundle properties){
			super.onPropertiesChanged(properties);
			mLowBitAmbient=properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
		}

		@Override
		public void onTimeTick(){
			super.onTimeTick();
			invalidate();
		}

		@Override
		public void onAmbientModeChanged(boolean inAmbientMode){
			super.onAmbientModeChanged(inAmbientMode);
			if(mAmbient!=inAmbientMode){
				mAmbient=inAmbientMode;
				if(mLowBitAmbient){
					paint.setAntiAlias(!inAmbientMode);
				}
				if(!inAmbientMode){
					ambientExitTime=System.currentTimeMillis();
					justExitedAmbient=true;
				}
				invalidate();
			}

			// Whether the timer should be running depends on whether we're visible (as well as
			// whether we're in ambient mode), so we may need to start or stop the timer.
			updateTimer();
		}

		/**
		 * Captures tap event (and tap type) and toggles the background color if the user finishes
		 * a tap.
		 */
		@Override
		public void onTapCommand(int tapType, int x, int y, long eventTime){
			switch(tapType){
				case TAP_TYPE_TOUCH:
					// The user has started touching the screen.
					break;
				case TAP_TYPE_TOUCH_CANCEL:
					// The user has started a different gesture or otherwise cancelled the tap.
					break;
				case TAP_TYPE_TAP:
					// The user has completed the tap gesture.
					for(int i=0;i<digitBounds.length;i++){
						if(digitBounds[i].contains(x, y))
							colorAnimStartTimes[i]=System.currentTimeMillis();
					}
					break;
			}
			invalidate();
		}

		private void drawDigitLines(Canvas canvas, float[] path, float coloredPart, float coloredPartOffset){
			if(coloredPart<=0.08f){
				paint.setColor(COLORS[0]);
				canvas.drawLines(path, paint);
				return;
			}
			int count=Math.round(path.length*coloredPart)/4;
			count-=count%4;
			int offset=Math.round(System.currentTimeMillis()%2000/2000f*path.length)-Math.round((count*4)*coloredPartOffset);
			if(offset<0)
				offset=path.length+offset;
			offset-=offset%4;
			for(int i=0;i<4;i++){
				paint.setColor(COLORS[i+1]);
				int start=offset+count*i;
				int end=offset+count*i+count;
				start%=path.length;
				end%=path.length;
				if(i==3 && coloredPart==1){
					end=offset==0 ? path.length : offset;
				}
				if(start<end){
					canvas.drawLines(path, start, end-start, paint);
				}else{
					canvas.drawLines(path, start, path.length-start, paint);
					canvas.drawLines(path, 0, end, paint);
				}
			}
			if(coloredPart<1){
				int start=count*4+offset;
				start%=path.length;
				int end=offset==0 ? path.length : offset;
				paint.setColor(COLORS[0]);
				if(start<end){
					canvas.drawLines(path, start, end-start, paint);
				}else{
					canvas.drawLines(path, start, path.length-start, paint);
					canvas.drawLines(path, 0, end, paint);
				}
			}
		}

		private void drawDigit(Canvas canvas, float x, float y, int digit, float animFraction, int animTo, float coloredPart, int index){
			canvas.save();
			canvas.translate(x, y);
			if(animFraction==0){
				if(coloredPart==0){
					paint.setColor(mAmbient ? 0xFFFFFFFF : COLORS[0]);
					canvas.drawBitmap(cachedDigits[digit], -digitSize, -digitSize, paint);
				}else{
					drawDigitLines(canvas, paths[digit], coloredPart, animatingColorOut[index] ? 1 : 0);
				}
			}else{
				float[] from=paths[digit];
				float[] to=paths[animTo];
				for(int i=0;i<from.length;i++){
					tmpDigit[i]=from[i]*(1-animFraction)+to[i]*animFraction;
				}
				drawDigitLines(canvas, tmpDigit, coloredPart, animatingColorOut[index] ? 1 : 0);
			}
			canvas.restore();
		}

		private float getAnimTime(int index){
			if(System.currentTimeMillis()-animStartTimes[index]>400 || mAmbient){
				return 0;
			}
			return interpolator.getInterpolation((System.currentTimeMillis()-animStartTimes[index])/400f);
		}

		private float getColorAnimTime(int index){
			if(System.currentTimeMillis()-colorAnimStartTimes[index]>5000 || mAmbient){
				return 0;
			}
			float t=(System.currentTimeMillis()-colorAnimStartTimes[index])/5000f;
			animatingColorOut[index]=false;
			if(t<.3f){
				return decelerate.getInterpolation(t/.3f);
			}else if(t>.7f){
				animatingColorOut[index]=true;
				return decelerate.getInterpolation(1-(t-.7f)/.3f);
			}
			return 1;
		}

		private boolean isAnimating(int index){
			return !mAmbient && System.currentTimeMillis()-animStartTimes[index]<=400;
		}

		@Override
		public void onDraw(Canvas canvas, Rect bounds){
			canvas.drawColor(Color.BLACK);

			time.setToNow();
			if(!DateFormat.is24HourFormat(IOWatchFace.this)){
				if(time.hour>12)
					time.hour-=12;
			}

			float y=mAmbient ? bounds.centerY() : (bounds.centerY()-digitSize*1.3f*decelerate.getInterpolation(Math.min(1, (System.currentTimeMillis()-ambientExitTime)/300f)));

			if(prevDigits[0]!=time.hour/10){
				animStartTimes[0]=System.currentTimeMillis();
				colorAnimStartTimes[0]=System.currentTimeMillis();
				animFromDigits[0]=prevDigits[0];
			}
			if(prevDigits[1]!=time.hour%10){
				animStartTimes[1]=System.currentTimeMillis();
				colorAnimStartTimes[1]=System.currentTimeMillis();
				animFromDigits[1]=prevDigits[1];
			}
			if(prevDigits[2]!=time.minute/10){
				animStartTimes[2]=System.currentTimeMillis();
				colorAnimStartTimes[2]=System.currentTimeMillis();
				animFromDigits[2]=prevDigits[2];
			}
			if(prevDigits[3]!=time.minute%10){
				animStartTimes[3]=System.currentTimeMillis();
				colorAnimStartTimes[3]=System.currentTimeMillis();
				animFromDigits[3]=prevDigits[3];
			}

			drawDigit(canvas, bounds.width()/2-digitSize*3.4f, y, isAnimating(0) ? animFromDigits[0] : time.hour/10, getAnimTime(0), time.hour/10, getColorAnimTime(0), 0);
			drawDigit(canvas, bounds.width()/2-digitSize*1.3f, y, isAnimating(1) ? animFromDigits[1] : time.hour%10, getAnimTime(1), time.hour%10, getColorAnimTime(1), 1);
			drawDigit(canvas, bounds.width()/2+digitSize*1.3f, y, isAnimating(2) ? animFromDigits[2] : time.minute/10, getAnimTime(2), time.minute/10, getColorAnimTime(2), 2);
			drawDigit(canvas, bounds.width()/2+digitSize*3.4f, y, isAnimating(3) ? animFromDigits[3] : time.minute%10, getAnimTime(3), time.minute%10, getColorAnimTime(3), 3);

			digitBounds[0].set(bounds.width()/2-digitSize*4.4f, y-digitSize, bounds.width()/2-digitSize*2.4f, y+digitSize);
			digitBounds[1].set(bounds.width()/2-digitSize*2.3f, y-digitSize, bounds.width()/2-digitSize*0.3f, y+digitSize);
			digitBounds[2].set(bounds.width()/2+digitSize*0.3f, y-digitSize, bounds.width()/2+digitSize*2.3f, y+digitSize);
			digitBounds[3].set(bounds.width()/2+digitSize*2.4f, y-digitSize, bounds.width()/2+digitSize*4.4f, y+digitSize);

			prevDigits[0]=time.hour/10;
			prevDigits[1]=time.hour%10;
			prevDigits[2]=time.minute/10;
			prevDigits[3]=time.minute%10;

			if(!mAmbient){
				if(prevDigits[4]!=time.second/10 && !justExitedAmbient){
					animStartTimes[4]=System.currentTimeMillis();
					animFromDigits[4]=prevDigits[4];
				}
				if(prevDigits[5]!=time.second%10 && !justExitedAmbient){
					animStartTimes[5]=System.currentTimeMillis();
					animFromDigits[5]=prevDigits[5];
				}
				COLORS[0]=0;
				drawDigit(canvas, bounds.width()/2+digitSize*1.3f, y+digitSize*2.6f, isAnimating(4) ? animFromDigits[4] : (time.second/10), getAnimTime(4), time.second/10, decelerate.getInterpolation(Math.min(1, (System.currentTimeMillis()-ambientExitTime)/300f)), 4);
				drawDigit(canvas, bounds.width()/2+digitSize*3.4f, y+digitSize*2.6f, isAnimating(5) ? animFromDigits[5] : (time.second%10), getAnimTime(5), time.second%10, decelerate.getInterpolation(Math.min(1, (System.currentTimeMillis()-ambientExitTime)/300f)), 5);
				COLORS[0]=0xFFFFFFFF;
				invalidate();
				prevDigits[4]=time.second/10;
				prevDigits[5]=time.second%10;
				justExitedAmbient=false;
			}
		}

		/**
		 * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
		 * or stops it if it shouldn't be running but currently is.
		 */
		private void updateTimer(){
			mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
			if(shouldTimerBeRunning()){
				mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
			}
		}

		/**
		 * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
		 * only run when we're visible and in interactive mode.
		 */
		private boolean shouldTimerBeRunning(){
			return isVisible() && !isInAmbientMode();
		}

		/**
		 * Handle updating the time periodically in interactive mode.
		 */
		private void handleUpdateTimeMessage(){
			invalidate();
			if(shouldTimerBeRunning()){
				long timeMs=System.currentTimeMillis();
				long delayMs=INTERACTIVE_UPDATE_RATE_MS
						-(timeMs%INTERACTIVE_UPDATE_RATE_MS);
				mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
			}
		}
	}
}
