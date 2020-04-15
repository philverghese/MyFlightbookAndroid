/*
	MyFlightbook for Android - provides native access to MyFlightbook
	pilot's logbook
    Copyright (C) 2017-2019 MyFlightbook, LLC

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.myflightbook.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ShareCompat;
import android.support.v4.content.ContextCompat;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.myflightbook.android.WebServices.AircraftSvc;
import com.myflightbook.android.WebServices.AuthToken;
import com.myflightbook.android.WebServices.CommitFlightSvc;
import com.myflightbook.android.WebServices.CustomPropertyTypesSvc;
import com.myflightbook.android.WebServices.DeleteFlightSvc;
import com.myflightbook.android.WebServices.FlightPropertiesSvc;
import com.myflightbook.android.WebServices.MFBSoap;
import com.myflightbook.android.WebServices.RecentFlightsSvc;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import Model.Aircraft;
import Model.Airport;
import Model.CustomPropertyType;
import Model.DecimalEdit;
import Model.DecimalEdit.CrossFillDelegate;
import Model.FlightProperty;
import Model.GPSSim;
import Model.LatLong;
import Model.LogbookEntry;
import Model.MFBConstants;
import Model.MFBFlightListener;
import Model.MFBImageInfo;
import Model.MFBImageInfo.PictureDestination;
import Model.MFBLocation;
import Model.MFBUtil;
import Model.PropertyTemplate;
import Model.SunriseSunsetTimes;

public class ActNewFlight extends ActMFBForm implements android.view.View.OnClickListener, MFBFlightListener.ListenerFragmentDelegate,
        DlgDatePicker.DateTimeUpdate, PropertyEdit.PropertyListener, ActMFBForm.GallerySource, CrossFillDelegate, MFBMain.Invalidatable {

    private Aircraft[] m_rgac = null;
    private LogbookEntry m_le = null;
    private HashSet<PropertyTemplate> m_activeTemplates = new HashSet<>();
    private boolean needsDefaultTemplates = true;

    public final static String PROPSFORFLIGHTID = "com.myflightbook.android.FlightPropsID";
    public final static String PROPSFORFLIGHTEXISTINGID = "com.myflightbook.android.FlightPropsIDExisting";
    public final static String PROPSFORFLIGHTCROSSFILLVALUE = "com.myflightbook.android.FlightPropsXFill";
    public final static String TACHFORCROSSFILLVALUE = "com.myflightbook.android.TachStartXFill";
    private static final int EDIT_PROPERTIES_ACTIVITY_REQUEST_CODE = 48329;
    private static final int CHOOSE_TEMPLATE_ACTIVITY_REQUEST_CODE = 29370;

    // current state of pause/play and accumulated night
    public static Boolean fPaused = false;
    private static long dtPauseTime = 0;
    private static long dtTimeOfLastPause = 0;
    public static double accumulatedNight = 0.0;

    // pause/play state
    static private final String m_KeysIsPaused = "flightIsPaused";
    static private final String m_KeysPausedTime = "totalPauseTime";
    static private final String m_KeysTimeOfLastPause = "timeOfLastPause";
    static private final String m_KeysAccumulatedNight = "accumulatedNight";

    // Expand state of "in the cockpit"
    static private final String m_KeyShowInCockpit = "inTheCockpit";

    public static final long lastNewFlightID = LogbookEntry.ID_NEW_FLIGHT;

    private TextView txtQuality, txtStatus, txtSpeed, txtAltitude, txtSunrise, txtSunset, txtLatitude, txtLongitude;
    private ImageView imgRecording;

    private Handler m_HandlerUpdateTimer = null;
    private Runnable m_UpdateElapsedTimeTask = null;

    private static class DeleteTask extends AsyncTask<Void, String, MFBSoap> implements MFBSoap.MFBSoapProgressUpdate {
        private ProgressDialog m_pd = null;
        private Object m_Result = null;
        private final int m_idFlight;
        private final AsyncWeakContext<ActNewFlight> m_ctxt;

        DeleteTask(Context c, ActNewFlight act, int idFlight) {
            super();
            m_ctxt = new AsyncWeakContext<>(c, act);
            m_idFlight = idFlight;
        }

        @Override
        protected MFBSoap doInBackground(Void... params) {
            DeleteFlightSvc dfs = new DeleteFlightSvc();
            dfs.m_Progress = this;
            dfs.DeleteFlight(AuthToken.m_szAuthToken, m_idFlight, m_ctxt.getContext());
            m_Result = (dfs.getLastError().length() == 0);

            return dfs;
        }

        protected void onPreExecute() {
            Context c = m_ctxt.getContext();
            if (c != null)
                m_pd = MFBUtil.ShowProgress(c, c.getString(R.string.prgDeletingFlight));
        }

        protected void onPostExecute(MFBSoap svc) {
            ActNewFlight act = m_ctxt.getCallingActivity();
            if (act == null)
                return;

            if ((Boolean) m_Result) {
                RecentFlightsSvc.ClearCachedFlights();
                MFBMain.invalidateCachedTotals();
                act.finish();
            } else {
                MFBUtil.Alert(act, act.getString(R.string.txtError), svc.getLastError());
            }

            try {
                if (m_pd != null)
                    m_pd.dismiss();
            } catch (Exception e) {
                Log.e(MFBConstants.LOG_TAG, Log.getStackTraceString(e));
            }
        }

        protected void onProgressUpdate(String... msg) {
            m_pd.setMessage(msg[0]);
        }

        public void NotifyProgress(int percentageComplete, String szMsg) {
            this.publishProgress(szMsg);
        }
    }

    private static class SubmitTask extends AsyncTask<Void, String, MFBSoap> implements MFBSoap.MFBSoapProgressUpdate {
        private ProgressDialog m_pd = null;
        private Object m_Result = null;
        private LogbookEntry lelocal = null;
        private LogbookEntry m_le;
        private final AsyncWeakContext<ActNewFlight> m_ctxt;
        private Boolean fIsNew = false;

        SubmitTask(Context c, ActNewFlight act, LogbookEntry le) {
            super();
            m_ctxt = new AsyncWeakContext<>(c, act);
            m_le = le;
        }

        @Override
        protected MFBSoap doInBackground(Void... params) {
            CommitFlightSvc cf = new CommitFlightSvc();
            cf.m_Progress = this;
            m_Result = cf.FCommitFlight(AuthToken.m_szAuthToken, m_le, m_ctxt.getContext());
            return cf;
        }

        protected void onPreExecute() {
            Context c = m_ctxt.getContext();
            if (c != null)
                m_pd = MFBUtil.ShowProgress(c, c.getString(R.string.prgSavingFlight));
            lelocal = m_le; // hold onto a reference to m_le.
            fIsNew = lelocal.IsNewFlight(); // cache this because after being successfully saved, it will no longer be new!
        }

        protected void onPostExecute(MFBSoap svc) {
            ActNewFlight act = m_ctxt.getCallingActivity();
            Context c = m_ctxt.getContext();
            if (act == null || c == null || act.getActivity() == null || !act.isAdded() || act.isDetached())
                return;

            if ((Boolean) m_Result) {
                MFBMain.invalidateCachedTotals();

                // success, so we our cached recents are invalid
                RecentFlightsSvc.ClearCachedFlights();

                DialogInterface.OnClickListener ocl;

                // the flight was successfully saved, so delete any local copy regardless
                lelocal.DeletePendingFlight();

                if (fIsNew) {
                    // Reset the flight and we stay on this page
                    act.ResetFlight(true);
                    ocl = (d, id) -> d.cancel();
                } else {
                    // no need to reset the current flight because we will finish.
                    ocl = (d, id) -> {
                        d.cancel();
                        act.finish();
                    };
                    lelocal = m_le = null; // so that onPause won't cause it to be saved on finish() call.
                }
                new AlertDialog.Builder(act.getActivity(), R.style.MFBDialog)
                        .setMessage(c.getString(R.string.txtSavedFlight))
                        .setTitle(c.getString(R.string.txtSuccess))
                        .setNegativeButton("OK", ocl)
                        .create().show();
            } else {
                MFBUtil.Alert(act, c.getString(R.string.txtError), svc.getLastError());
            }

            try {
                if (m_pd != null)
                    m_pd.dismiss();
            } catch (Exception e) {
                Log.e(MFBConstants.LOG_TAG, Log.getStackTraceString(e));
            }
        }

        protected void onProgressUpdate(String... msg) {
            m_pd.setMessage(msg[0]);
        }

        public void NotifyProgress(int percentageComplete, String szMsg) {
            this.publishProgress(szMsg);
        }
    }

    private static class GetDigitizedSigTask extends AsyncTask<String, Void, Bitmap> {
        final AsyncWeakContext<ImageView> m_ctxt;

        GetDigitizedSigTask(ImageView iv) {
            super();
            m_ctxt = new AsyncWeakContext<>(null, iv);
        }

        protected Bitmap doInBackground(String... urls) {
            String url = urls[0];
            Bitmap bm = null;
            try {
                InputStream str = new java.net.URL(url).openStream();
                bm = BitmapFactory.decodeStream(str);
            } catch (Exception e) {
                Log.e(MFBConstants.LOG_TAG, e.getMessage());
            }
            return bm;
        }

        protected void onPostExecute(Bitmap result) {
            ImageView iv = m_ctxt.getCallingActivity();
            if (iv != null)
                iv.setImageBitmap(result);
        }
    }

    private static class RefreshAircraftTask extends AsyncTask<Void, Void, MFBSoap> {
        private ProgressDialog m_pd = null;
        Object m_Result = null;
        final AsyncWeakContext<ActNewFlight> m_ctxt;

        RefreshAircraftTask(Context c, ActNewFlight anf) {
            super();
            m_ctxt = new AsyncWeakContext<>(c, anf);
        }

        @Override
        protected MFBSoap doInBackground(Void... params) {
            AircraftSvc as = new AircraftSvc();
            m_Result = as.AircraftForUser(AuthToken.m_szAuthToken, m_ctxt.getContext());
            return as;
        }

        protected void onPreExecute() {
            m_pd = MFBUtil.ShowProgress(m_ctxt.getContext(), m_ctxt.getContext().getString(R.string.prgAircraft));
        }

        protected void onPostExecute(MFBSoap svc) {
            try {
                if (m_pd != null)
                    m_pd.dismiss();
            } catch (Exception e) {
                Log.e(MFBConstants.LOG_TAG, Log.getStackTraceString(e));
            }

            ActNewFlight anf = m_ctxt.getCallingActivity();
            if (anf == null || !anf.isAdded() || anf.isDetached() || anf.getActivity() == null)
                return;

            Aircraft[] rgac = (Aircraft[]) m_Result;
            if (rgac == null)
                MFBUtil.Alert(anf, anf.getString(R.string.txtError), svc.getLastError());
            else {
                anf.refreshAircraft(rgac);
            }
        }
    }

    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        this.setHasOptionsMenu(true);
        return inflater.inflate(R.layout.newflight, container, false);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (m_le == null || m_le.IsNewFlight())
            MFBMain.SetInProgressFlightActivity(getContext(), null);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        AddListener(R.id.btnFlightSet);
        AddListener(R.id.btnFlightStartSet);
        AddListener(R.id.btnEngineStartSet);
        AddListener(R.id.btnFlightEndSet);
        AddListener(R.id.btnEngineEndSet);
        AddListener(R.id.btnProps);
        AddListener(R.id.btnAppendNearest);

        findViewById(R.id.btnAppendNearest).setOnLongClickListener((v) -> {
            AppendAdHoc();
            return true;
        });

        // Expand/collapse
        AddListener(R.id.txtViewInTheCockpit);
        AddListener(R.id.txtImageHeader);
        AddListener(R.id.txtPinnedPropertiesHeader);
        AddListener(R.id.txtSignatureHeader);

        enableCrossFill(R.id.txtNight);
        enableCrossFill(R.id.txtSimIMC);
        enableCrossFill(R.id.txtIMC);
        enableCrossFill(R.id.txtXC);
        enableCrossFill(R.id.txtDual);
        enableCrossFill(R.id.txtGround);
        enableCrossFill(R.id.txtCFI);
        enableCrossFill(R.id.txtSIC);
        enableCrossFill(R.id.txtPIC);
        enableCrossFill(R.id.txtHobbsStart);

        findViewById(R.id.txtTotal).setOnLongClickListener(v -> {
            Intent i = new Intent(getActivity(), ActTimeCalc.class);
            i.putExtra(ActTimeCalc.INITIAL_TIME, DoubleFromField(R.id.txtTotal));
            startActivityForResult(i, ActTimeCalc.TIME_CALC_REQUEST_CODE);
            return true;
        });

        findViewById(R.id.btnFlightSet).setOnLongClickListener(v -> {
            this.resetDateOfFlight();
            return true;
        });

        ImageButton b = (ImageButton) findViewById(R.id.btnPausePlay);
        b.setOnClickListener(this);
        b = (ImageButton) findViewById(R.id.btnViewOnMap);
        b.setOnClickListener(this);
        b = (ImageButton) findViewById(R.id.btnAddApproach);
        b.setOnClickListener(this);

        // cache these views for speed.
        txtQuality = (TextView) findViewById(R.id.txtFlightGPSQuality);
        txtStatus = (TextView) findViewById(R.id.txtFlightStatus);
        txtSpeed = (TextView) findViewById(R.id.txtFlightSpeed);
        txtAltitude = (TextView) findViewById(R.id.txtFlightAltitude);
        txtSunrise = (TextView) findViewById(R.id.txtSunrise);
        txtSunset = (TextView) findViewById(R.id.txtSunset);
        txtLatitude = (TextView) findViewById(R.id.txtLatitude);
        txtLongitude = (TextView) findViewById(R.id.txtLongitude);
        imgRecording = (ImageView) findViewById(R.id.imgRecording);

        // get notification of hobbs changes, or at least focus changes
        OnFocusChangeListener s = (v, hasFocus) -> {
            if (!hasFocus) onHobbsChanged(v);
        };

        findViewById(R.id.txtHobbsStart).setOnFocusChangeListener(s);
        findViewById(R.id.txtHobbsEnd).setOnFocusChangeListener(s);

        Intent i = Objects.requireNonNull(getActivity()).getIntent();
        int idFlightToView = i.getIntExtra(ActRecentsWS.VIEWEXISTINGFLIGHTID, 0);
        long idLocalFlightToView = i.getLongExtra(ActRecentsWS.VIEWEXISTINGFLIGHTLOCALID, 0);
        boolean fIsNewFlight = (idFlightToView == 0);
        if (!fIsNewFlight && m_rgac == null)
            m_rgac = (new AircraftSvc()).getCachedAircraft();

        Log.w(MFBConstants.LOG_TAG, String.format("ActNewFlight - onCreate - Viewing flight idflight=%d, idlocal=%d", idFlightToView, idLocalFlightToView));

        ((Spinner) findViewById(R.id.spnAircraft)).setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Aircraft ac = (Aircraft) parent.getSelectedItem();
                if (ac != null && m_le.idAircraft != ac.AircraftID) {
                    FromView();
                        m_le.idAircraft = ac.AircraftID;
                    updateTemplatesForAircraft(false);
                    ToView();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // set for no focus.
        findViewById(R.id.btnFlightSet).requestFocus();

        if (fIsNewFlight) {
            // re-use the existing in-progress flight
            m_le = MFBMain.getNewFlightListener().getInProgressFlight(getActivity());
            MFBMain.registerNotifyResetAll(this);
            SharedPreferences pref = getActivity().getPreferences(Context.MODE_PRIVATE);
            Boolean fExpandCockpit = pref.getBoolean(m_KeyShowInCockpit, true);
            setExpandedState((TextView) findViewById(R.id.txtViewInTheCockpit), findViewById(R.id.sectInTheCockpit), fExpandCockpit, false);
        } else {
            // view an existing flight
            if (idFlightToView > 0) // existing flight
            {
                m_le = RecentFlightsSvc.GetCachedFlightByID(idFlightToView);
                if (m_le == null) {
                    // Navigate back
                    MFBUtil.Alert(this, getString(R.string.txtError), getString(R.string.errCannotFindFlight));
                    finish();
                    return;
                } else {
                    if (m_le.IsExistingFlight()) {
                        m_le.ToDB();    // ensure that this is in the database - above call could have pulled from cache
                        FlightProperty.RewritePropertiesForFlight(m_le.idLocalDB, m_le.rgCustomProperties);
                    }
                }

                setUpPropertiesForFlight();

                // get any existing props, if they're on the server
                if (m_le.IsExistingFlight())
                    m_le.SyncProperties();
            } else {
                m_le = new LogbookEntry(idLocalFlightToView);
                m_le.SyncProperties();
            }
        }

        if (m_le != null && m_le.rgFlightImages == null)
            m_le.getImagesForFlight();

        // Refresh aircraft on create.
        if (AuthToken.FIsValid() && (m_rgac == null || m_rgac.length == 0)) {
            RefreshAircraftTask rat = new RefreshAircraftTask(getContext(), this);
            rat.execute();
        }

        Log.w(MFBConstants.LOG_TAG, String.format("ActNewFlight - created, m_le is %s", m_le == null ? "null" : "non-null"));
    }

    private void enableCrossFill(int id) {
        DecimalEdit de = (DecimalEdit) findViewById(id);
        de.setDelegate(this);
    }

    public void CrossFillRequested(DecimalEdit sender) {
        FromView();
        if (sender.getId() == R.id.txtHobbsStart) {
            Double d = Aircraft.getHighWaterHobbsForAircraft(m_le.idAircraft);
            if (d > 0)
                sender.setDoubleValue(d);
        }
        else if (m_le.decTotal > 0)
            sender.setDoubleValue(m_le.decTotal);
        FromView();
    }

    private Aircraft[] SelectibleAircraft() {
        if (m_rgac == null)
            return null;

        List<Aircraft> lst = new ArrayList<>();
        for (Aircraft ac : m_rgac) {
            if (!ac.HideFromSelection || (m_le != null && ac.AircraftID == m_le.idAircraft))
                lst.add(ac);
        }
        return lst.toArray(new Aircraft[0]);
    }

    private  void refreshAircraft(Aircraft[] rgac) {
        m_rgac = rgac;

        Spinner spnAircraft = (Spinner) findViewById(R.id.spnAircraft);

        Aircraft[] rgFilteredAircraft = SelectibleAircraft();
        if (rgFilteredAircraft != null && rgFilteredAircraft.length > 0) {
            int pos = 0;
            for (int i = 0; i < rgFilteredAircraft.length; i++) {
                if (m_le.idAircraft == rgFilteredAircraft[i].AircraftID) {
                    pos = i;
                    break;
                }
            }
            // Create a list of the aircraft to show, which are the ones that are not hidden OR the active one for the flight
            ArrayAdapter<Aircraft> adapter = new ArrayAdapter<>(
                    Objects.requireNonNull(getActivity()), R.layout.mfbsimpletextitem, rgFilteredAircraft);
            spnAircraft.setAdapter(adapter);
            spnAircraft.setSelection(pos);
            // need to notifydatasetchanged or else setselection doesn't
            // update correctly.
            adapter.notifyDataSetChanged();
        } else {
            spnAircraft.setPrompt(getString(R.string.errNoAircraftFoundShort));
            MFBUtil.Alert(this, getString(R.string.txtError), getString(R.string.errMustCreateAircraft));
        }
    }

    public void onResume() {
        // refresh the aircraft list (will be cached if we already have it)
        // in case a new aircraft has been added.
        super.onResume();

        if (!AuthToken.FIsValid()) {
            DlgSignIn d = new DlgSignIn(getActivity());
            d.show();
        }

        if (m_rgac != null) {
            Aircraft[] rgac = (new AircraftSvc()).getCachedAircraft();
            if (rgac != null)
                m_rgac = rgac;
            refreshAircraft(m_rgac);
        }

        // fix up the link to the user's profile.
        AddListener(R.id.txtSocialNetworkHint);

        // Not sure why le can sometimes be empty here...
        if (m_le == null)
            m_le = MFBMain.getNewFlightListener().getInProgressFlight(getActivity());

        // show/hide GPS controls based on whether this is a new flight or existing.
        boolean fIsNewFlight = m_le.IsNewFlight();

        LinearLayout l = (LinearLayout) findViewById(R.id.sectGPS);
        l.setVisibility(fIsNewFlight ? View.VISIBLE : View.GONE);

        findViewById(R.id.btnAppendNearest).setVisibility(fIsNewFlight && MFBLocation.HasGPS(Objects.requireNonNull(getContext())) ? View.VISIBLE : View.GONE);
        ImageButton btnViewFlight = (ImageButton) findViewById(R.id.btnViewOnMap);
        btnViewFlight.setVisibility((MFBMain.HasMaps()) ? View.VISIBLE : View.GONE);

        if (fIsNewFlight) {
            // ensure that we keep the elapsed time up to date
            updatePausePlayButtonState();
            m_HandlerUpdateTimer = new Handler();
            m_UpdateElapsedTimeTask = new Runnable() {
                public void run() {
                    updateElapsedTime();
                    m_HandlerUpdateTimer.postDelayed(this, 1000);
                }
            };
            m_HandlerUpdateTimer.postDelayed(m_UpdateElapsedTimeTask, 1000);
        }

        setUpGalleryForFlight();

        RestoreState();

        // set the ensure that we are following the right numerical format
        setDecimalEditMode(R.id.txtCFI);
        setDecimalEditMode(R.id.txtDual);
        setDecimalEditMode(R.id.txtGround);
        setDecimalEditMode(R.id.txtIMC);
        setDecimalEditMode(R.id.txtNight);
        setDecimalEditMode(R.id.txtPIC);
        setDecimalEditMode(R.id.txtSIC);
        setDecimalEditMode(R.id.txtSimIMC);
        setDecimalEditMode(R.id.txtTotal);
        setDecimalEditMode(R.id.txtXC);

        // Make sure the date of flight is up-to-date
        if (m_le.isKnownEngineStart() || m_le.isKnownFlightStart())
            resetDateOfFlight();

        // First resume after create should pull in default templates;
        // subsequent resumes should NOT.
        updateTemplatesForAircraft(!needsDefaultTemplates);
        needsDefaultTemplates = false;  // reset this.

        ToView();

        // do this last to start GPS service
        if (fIsNewFlight)
            MFBMain.SetInProgressFlightActivity(getContext(), this);
    }

    private void setUpGalleryForFlight() {
        if (m_le.rgFlightImages == null)
            m_le.getImagesForFlight();
        setUpImageGallery(getGalleryID(), m_le.rgFlightImages, getGalleryHeader());
    }

    public void onPause() {
        super.onPause();

        // should only happen when we are returning from viewing an existing/pending flight, may have been submitted
        // either way, no need to save this
        if (m_le == null)
            return;

        FromView();
        saveCurrentFlight();
        Log.w(MFBConstants.LOG_TAG, String.format("Paused, landings are %d", m_le.cLandings));
        SaveState();

        // free up some scheduling resources.
        if (m_HandlerUpdateTimer != null)
            m_HandlerUpdateTimer.removeCallbacks(m_UpdateElapsedTimeTask);
    }

    private void RestoreState() {
        try {
            SharedPreferences mPrefs = Objects.requireNonNull(getActivity()).getPreferences(Activity.MODE_PRIVATE);

            ActNewFlight.fPaused = mPrefs.getBoolean(m_KeysIsPaused, false);
            ActNewFlight.dtPauseTime = mPrefs.getLong(m_KeysPausedTime, 0);
            ActNewFlight.dtTimeOfLastPause = mPrefs.getLong(m_KeysTimeOfLastPause, 0);
            ActNewFlight.accumulatedNight = mPrefs.getFloat(m_KeysAccumulatedNight, (float) 0.0);
        } catch (Exception e) {
            Log.e(MFBConstants.LOG_TAG, Log.getStackTraceString(e));
        }
    }

    private void SaveState() {
        // Save UI state changes to the savedInstanceState.
        // This bundle will be passed to onCreate if the process is
        // killed and restarted.
        SharedPreferences.Editor ed = Objects.requireNonNull(getActivity()).getPreferences(Activity.MODE_PRIVATE).edit();
        ed.putBoolean(m_KeysIsPaused, ActNewFlight.fPaused);
        ed.putLong(m_KeysPausedTime, ActNewFlight.dtPauseTime);
        ed.putLong(m_KeysTimeOfLastPause, ActNewFlight.dtTimeOfLastPause);
        ed.putFloat(m_KeysAccumulatedNight, (float) ActNewFlight.accumulatedNight);
        ed.apply();
    }

    public void saveCurrentFlight() {
        if (!m_le.IsExistingFlight())
            m_le.ToDB();

        // and we only want to save the current flightID if it is a new (not pending!) flight
        if (m_le.IsNewFlight())
            MFBMain.getNewFlightListener().saveCurrentFlightId(getActivity());
    }

    private void SetLogbookEntry(LogbookEntry le) {
        m_le = le;
        saveCurrentFlight();
        if (getView() == null)
            return;

        setUpGalleryForFlight();
        ToView();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        int idMenu;
        if (m_le != null) {
            if (m_le.IsExistingFlight())
                idMenu = R.menu.mfbexistingflightmenu;
            else if (m_le.IsNewFlight())
                idMenu = R.menu.mfbnewflightmenu;
            else if (m_le.IsQueuedFlight())
                idMenu = R.menu.mfbpendingflightmenu;
            else
                idMenu = R.menu.mfbpendingflightmenu;
            inflater.inflate(idMenu, menu);
        }
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = Objects.requireNonNull(getActivity()).getMenuInflater();
        inflater.inflate(R.menu.imagemenu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Should never happen.
        if (m_le == null)
            return false;

        // Handle item selection
        int menuId = item.getItemId();
        switch (menuId) {
            case R.id.menuUploadLater:
                SubmitFlight(true);
                return true;
            case R.id.menuResetFlight:
                if (m_le.idLocalDB > 0)
                    m_le.DeletePendingFlight();
                ResetFlight(false);
                return true;
            case R.id.menuSignFlight:
                try {
                    ActWebView.ViewURL(getActivity(), String.format(Locale.US, MFBConstants.urlSign,
                            MFBConstants.szIP,
                            m_le.idFlight,
                            URLEncoder.encode(AuthToken.m_szAuthToken, "UTF-8"),
                            MFBConstants.NightParam(getContext())));
                } catch (UnsupportedEncodingException ignored) {
                }
                return true;
            case R.id.btnDeleteFlight:
                new AlertDialog.Builder(getActivity(), R.style.MFBDialog)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.lblConfirm)
                        .setMessage(R.string.lblConfirmFlightDelete)
                        .setPositiveButton(R.string.lblOK, (dialog, which) -> {
                            if (m_le.IsPendingFlight()) {
                                m_le.DeletePendingFlight();
                                m_le = null; // clear this out since we're going to finish().
                                RecentFlightsSvc.ClearCachedFlights();
                                finish();
                            } else if (m_le.IsExistingFlight()) {
                                DeleteTask dt = new DeleteTask(getContext(), this, m_le.idFlight);
                                dt.execute();
                            }
                        })
                        .setNegativeButton(R.string.lblCancel, null)
                        .show();
                return true;
            case R.id.btnSubmitFlight:
            case R.id.btnUpdateFlight:
                SubmitFlight(false);
                return true;
            case R.id.menuTakePicture:
                takePictureClicked();
                return true;
            case R.id.menuTakeVideo:
                takeVideoClicked();
                return true;
            case R.id.menuChoosePicture:
                choosePictureClicked();
                return true;
            case R.id.menuChooseTemplate: {
                Intent i = new Intent(getActivity(), ViewTemplatesActivity.class);
                Bundle b = new Bundle();
                b.putSerializable(ActViewTemplates.ACTIVE_PROPERTYTEMPLATES, m_activeTemplates);
                i.putExtras(b);
                startActivityForResult(i, CHOOSE_TEMPLATE_ACTIVITY_REQUEST_CODE);
            }
                return true;
            case R.id.menuRepeatFlight:
            case R.id.menuReverseFlight: {
                assert m_le != null;
                LogbookEntry leNew = (menuId == R.id.menuRepeatFlight) ? m_le.Clone() : m_le.CloneAndReverse();
                leNew.idFlight = LogbookEntry.ID_QUEUED_FLIGHT_UNSUBMITTED;
                leNew.ToDB();
                FlightProperty.RewritePropertiesForFlight(leNew.idLocalDB, leNew.rgCustomProperties);
                leNew.SyncProperties();
                RecentFlightsSvc.ClearCachedFlights();
                new AlertDialog.Builder(ActNewFlight.this.getActivity(), R.style.MFBDialog)
                        .setMessage(getString(R.string.txtRepeatFlightComplete))
                        .setTitle(getString(R.string.txtSuccess))
                        .setNegativeButton("OK", (d, id) -> {
                            d.cancel();
                            finish();
                        })
                        .create().show();
                return true;
            }
            case R.id.menuSendFlight:
                sendFlight();
                return true;
            case R.id.menuShareFlight:
                shareFlight();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void sendFlight() {
        if (m_le == null || m_le.sendLink == null || m_le.sendLink.length() == 0) {
            MFBUtil.Alert(this, getString(R.string.txtError), getString(R.string.errCantSend));
            return;
        }

        ShareCompat.IntentBuilder.from(Objects.requireNonNull(getActivity()))
                .setType("message/rfc822")
                .setSubject(getString(R.string.sendFlightSubject))
                .setText(String.format(Locale.getDefault(), getString(R.string.sendFlightBody), m_le.sendLink))
                .setChooserTitle(getString(R.string.menuSendFlight))
                .startChooser();
    }

    private void shareFlight() {
        if (m_le == null || m_le.shareLink == null || m_le.shareLink.length() == 0) {
            MFBUtil.Alert(this, getString(R.string.txtError), getString(R.string.errCantShare));
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, String.format(Locale.getDefault(), "%s %s", m_le.szComments, m_le.szRoute).trim());
        intent.putExtra(Intent.EXTRA_TEXT, m_le.shareLink);

        startActivity(Intent.createChooser(intent,  getString(R.string.menuShareFlight)));
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuAddComment:
            case R.id.menuDeleteImage:
            case R.id.menuViewImage:
                return onImageContextItemSelected(item, this);
            default:
                break;
        }
        return true;
    }

    private final int PERMISSION_REQUEST_NEAREST = 10683;

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_NEAREST) {
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AppendNearest();
            }
            return;
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private Boolean checkGPSPermissions() {
        if (ContextCompat.checkSelfPermission(Objects.requireNonNull(getActivity()), android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            return true;

        // Should we show an explanation?
        requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_NEAREST);
        return false;
    }

    //region append to route
    // NOTE: I had been doing this with an AsyncTask, but it
    // wasn't thread safe with the database.  DB is pretty fast,
    // so we can just make sure we do all DB stuff on the main thread.
    private void AppendNearest() {
        assert MFBLocation.GetMainLocation() != null;

        if (checkGPSPermissions()) {
            TextView txtRoute = (TextView) findViewById(R.id.txtRoute);
            m_le.szRoute = Airport.AppendNearestToRoute(txtRoute.getText().toString(), MFBLocation.GetMainLocation().CurrentLoc());
            txtRoute.setText(m_le.szRoute);
        }
    }

    private void AppendAdHoc() {
        MFBLocation loc = MFBLocation.GetMainLocation();
        assert loc != null;

        if (!checkGPSPermissions())
            return;

        if (loc.CurrentLoc() == null)
            return;
        String szAdHoc = new LatLong(loc.CurrentLoc()).toAdHocLocString();
        TextView txtRoute = (TextView) findViewById(R.id.txtRoute);
        m_le.szRoute = Airport.AppendCodeToRoute(txtRoute.getText().toString(), szAdHoc);
        txtRoute.setText(m_le.szRoute);
    }
    //endregion

    private void takePictureClicked() {
        saveCurrentFlight();
        TakePicture();
    }

    private void takeVideoClicked() {
        saveCurrentFlight();
        TakeVideo();
    }

    private void choosePictureClicked() {
        saveCurrentFlight();
        ChoosePicture();
    }

    public void onClick(View v) {
        FromView();
        int id = v.getId();
        switch (id) {
            case R.id.btnEngineStartSet:
                if (!m_le.isKnownEngineStart()) {
                    m_le.dtEngineStart = MFBUtil.nowWith0Seconds();
                    EngineStart();
                } else
                    SetDateTime(id, m_le.dtEngineStart, this, DlgDatePicker.datePickMode.UTCDATETIME);
                break;
            case R.id.btnEngineEndSet:
                if (!m_le.isKnownEngineEnd()) {
                    m_le.dtEngineEnd = MFBUtil.nowWith0Seconds();
                    EngineStop();
                } else
                    SetDateTime(id, m_le.dtEngineEnd, this, DlgDatePicker.datePickMode.UTCDATETIME);
                break;
            case R.id.btnFlightStartSet:
                if (!m_le.isKnownFlightStart()) {
                    m_le.dtFlightStart = MFBUtil.nowWith0Seconds();
                    FlightStart();
                } else
                    SetDateTime(id, m_le.dtFlightStart, this, DlgDatePicker.datePickMode.UTCDATETIME);
                break;
            case R.id.btnFlightEndSet:
                if (!m_le.isKnownFlightEnd()) {
                    m_le.dtFlightEnd = MFBUtil.nowWith0Seconds();
                    FlightStop();
                } else
                    SetDateTime(id, m_le.dtFlightEnd, this, DlgDatePicker.datePickMode.UTCDATETIME);
                break;
            case R.id.btnFlightSet:
                DlgDatePicker dlg = new DlgDatePicker(getActivity(), DlgDatePicker.datePickMode.LOCALDATEONLY, m_le.dtFlight);
                dlg.m_delegate = this;
                dlg.m_id = id;
                dlg.show();
                break;
            case R.id.btnProps:
                ViewPropsForFlight();
                break;
            case R.id.btnAppendNearest:
                AppendNearest();
                break;
            case R.id.btnAddApproach: {
                Intent i = new Intent(getActivity(), ActAddApproach.class);
                i.putExtra(ActAddApproach.AIRPORTSFORAPPROACHES, m_le.szRoute);
                startActivityForResult(i, ActAddApproach.APPROACH_DESCRIPTION_REQUEST_CODE);
            }
            break;
            case R.id.btnViewOnMap:
                Intent i = new Intent(getActivity(), ActFlightMap.class);
                i.putExtra(ActFlightMap.ROUTEFORFLIGHT, m_le.szRoute);
                i.putExtra(ActFlightMap.EXISTINGFLIGHTID, m_le.IsExistingFlight() ? m_le.idFlight : 0);
                i.putExtra(ActFlightMap.PENDINGFLIGHTID, m_le.IsPendingFlight() ? m_le.idLocalDB : 0);
                i.putExtra(ActFlightMap.NEWFLIGHTID, m_le.IsNewFlight() ? LogbookEntry.ID_NEW_FLIGHT : 0);
                i.putExtra(ActFlightMap.ALIASES, "");
                startActivityForResult(i, ActFlightMap.REQUEST_ROUTE);
                break;
            case R.id.btnPausePlay:
                toggleFlightPause();
                break;
            case R.id.txtViewInTheCockpit: {
                View target = findViewById(R.id.sectInTheCockpit);
                boolean fExpandCockpit = target.getVisibility() != View.VISIBLE;

                if (m_le != null && m_le.IsNewFlight()) {
                    SharedPreferences.Editor e = Objects.requireNonNull(getActivity()).getPreferences(Context.MODE_PRIVATE).edit();
                    e.putBoolean(m_KeyShowInCockpit, fExpandCockpit);
                    e.apply();
                }
                setExpandedState((TextView) v, target, fExpandCockpit);
            }
            break;
            case R.id.txtImageHeader: {
                View target = findViewById(R.id.tblImageTable);
                setExpandedState((TextView) v, target, target.getVisibility() != View.VISIBLE);
            }
            break;
            case R.id.txtSignatureHeader: {
                View target = findViewById(R.id.sectSignature);
                setExpandedState((TextView) v, target, target.getVisibility() != View.VISIBLE);
            }
            break;
            case R.id.txtPinnedPropertiesHeader: {
                View target = findViewById(R.id.sectPinnedProperties);
                setExpandedState((TextView) v, target, target.getVisibility() != View.VISIBLE);
            }
            break;
            case R.id.txtSocialNetworkHint: {
                String szURLProfile = MFBConstants.AuthRedirWithParams("d=profile&pane=social", getContext());
                ActWebView.ViewURL(getActivity(), szURLProfile);
            }
            default:
                break;
        }
        ToView();
    }

    @SuppressWarnings("unchecked")
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case CAPTURE_IMAGE_ACTIVITY_REQUEST_CODE:
            case CAPTURE_VIDEO_ACTIVITY_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK)
                    AddCameraImage(m_TempFilePath, requestCode == CAPTURE_VIDEO_ACTIVITY_REQUEST_CODE);
                break;
            case SELECT_IMAGE_ACTIVITY_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK)
                    AddGalleryImage(data);
            case EDIT_PROPERTIES_ACTIVITY_REQUEST_CODE:
                setUpPropertiesForFlight();
                if (MFBLocation.fPrefAutoFillTime == MFBLocation.AutoFillOptions.BlockTime)
                    AutoTotals();
                break;
            case ActTimeCalc.TIME_CALC_REQUEST_CODE:
                SetDoubleForField(R.id.txtTotal, m_le.decTotal = data.getDoubleExtra(ActTimeCalc.COMPUTED_TIME, m_le.decTotal));
                break;
            case ActFlightMap.REQUEST_ROUTE:
                m_le.szRoute = data.getStringExtra(ActFlightMap.ROUTEFORFLIGHT);
                SetStringForField(R.id.txtRoute, m_le.szRoute);
                break;
            case CHOOSE_TEMPLATE_ACTIVITY_REQUEST_CODE:
                Bundle b = data.getExtras();
                try {
                    m_activeTemplates = (HashSet<PropertyTemplate>) Objects.requireNonNull(b).getSerializable(ActViewTemplates.ACTIVE_PROPERTYTEMPLATES);
                    updateTemplatesForAircraft(true);
                    ToView();
                } catch (ClassCastException ex) {
                    Log.e(MFBConstants.LOG_TAG, ex.getMessage());
                }
                break;
            case ActAddApproach.APPROACH_DESCRIPTION_REQUEST_CODE:
                String approachDesc = data.getStringExtra(ActAddApproach.APPROACHDESCRIPTIONRESULT);
                if (approachDesc.length() > 0) {
                    m_le.AddApproachDescription(approachDesc);

                    int cApproachesToAdd = data.getIntExtra(ActAddApproach.APPROACHADDTOTOTALSRESULT, 0);
                    if (cApproachesToAdd > 0) {
                        m_le.cApproaches += cApproachesToAdd;
                        SetIntForField(R.id.txtApproaches, m_le.cApproaches);
                    }
                    ToView();
                }
                break;
            default:
                break;
        }
    }

    private void ViewPropsForFlight() {
        Intent i = new Intent(getActivity(), ActViewProperties.class);
        i.putExtra(PROPSFORFLIGHTID, m_le.idLocalDB);
        i.putExtra(PROPSFORFLIGHTEXISTINGID, m_le.idFlight);
        i.putExtra(PROPSFORFLIGHTCROSSFILLVALUE, m_le.decTotal);
        i.putExtra(TACHFORCROSSFILLVALUE, Aircraft.getHighWaterTachForAircraft(m_le.idAircraft));
        startActivityForResult(i, EDIT_PROPERTIES_ACTIVITY_REQUEST_CODE);
    }

    private void onHobbsChanged(View v) {
        if (m_le != null && MFBLocation.fPrefAutoFillTime == MFBLocation.AutoFillOptions.HobbsTime) {
            EditText txtHobbsStart = (EditText) findViewById(R.id.txtHobbsStart);
            EditText txtHobbsEnd = (EditText) findViewById(R.id.txtHobbsEnd);
            double newHobbsStart = DoubleFromField(R.id.txtHobbsStart);
            double newHobbsEnd = DoubleFromField(R.id.txtHobbsEnd);

            if ((v == txtHobbsStart && newHobbsStart != m_le.hobbsStart) ||
                    (v == txtHobbsEnd && newHobbsEnd != m_le.hobbsEnd))
                AutoTotals();
        }
    }

    public void updateDate(int id, Date dt) {
        FromView();

        boolean fEngineChanged = false;
        boolean fFlightChanged = false;

        dt = MFBUtil.removeSeconds(dt);

        switch (id) {
            case R.id.btnEngineStartSet:
                m_le.dtEngineStart = dt;
                fEngineChanged = true;
                resetDateOfFlight();
                break;
            case R.id.btnEngineEndSet:
                m_le.dtEngineEnd = dt;
                fEngineChanged = true;
                ShowRecordingIndicator();
                break;
            case R.id.btnFlightStartSet:
                m_le.dtFlightStart = dt;
                resetDateOfFlight();
                fFlightChanged = true;
                break;
            case R.id.btnFlightEndSet:
                m_le.dtFlightEnd = dt;
                fFlightChanged = true;
                break;
            case R.id.btnFlightSet:
                m_le.dtFlight = dt;
                break;
            default:
                break;
        }
        ToView();

        // keep auto-updated hobbs and totals in sync
        switch (MFBLocation.fPrefAutoFillHobbs) {
            case EngineTime:
                if (fEngineChanged)
                    AutoHobbs();
                break;
            case FlightTime:
                if (fFlightChanged)
                    AutoHobbs();
                break;
            default:
                break;
        }

        switch (MFBLocation.fPrefAutoFillTime) {
            case EngineTime:
                if (fEngineChanged)
                    AutoTotals();
                break;
            case FlightTime:
                if (fFlightChanged)
                    AutoTotals();
                break;
            case HobbsTime:
                // Should have been caught by autohobbs above
                break;
            case FlightStartToEngineEnd:
                AutoTotals();
                break;
            default:
                break;
        }
    }

    private int ValidateAircraftID(int id) {
        int idAircraftToUse = -1;

        if (m_rgac != null) {
            for (Aircraft ac : m_rgac)
                if (ac.AircraftID == id) {
                    idAircraftToUse = id;
                    break;
                }
        }

        return idAircraftToUse;
    }

    private void ResetFlight(Boolean fCarryHobbs) {
        // start up a new flight with the same aircraft ID and public setting.
        // first, validate that the aircraft is still OK for the user
        double hobbsEnd = m_le.hobbsEnd;
        LogbookEntry leNew = new LogbookEntry(ValidateAircraftID(m_le.idAircraft), m_le.fPublic);
        if (fCarryHobbs)
            leNew.hobbsStart = hobbsEnd;
        m_activeTemplates.clear();
        m_le.idAircraft = leNew.idAircraft; // so that updateTemplatesForAircraft works
        updateTemplatesForAircraft(false);
        SetLogbookEntry(leNew);
        MFBMain.getNewFlightListener().setInProgressFlight(leNew);
        saveCurrentFlight();

        // flush any pending flight data
        MFBLocation.GetMainLocation().ResetFlightData();

        // and flush any pause/play data
        fPaused = false;
        dtPauseTime = 0;
        dtTimeOfLastPause = 0;
        ActNewFlight.accumulatedNight = 0.0;
    }

    private void SubmitFlight(Boolean fForcePending) {
        FromView();

        Activity a = getActivity();
        if (a != null && a.getCurrentFocus() != null)
            a.getCurrentFocus().clearFocus();   // force any in-progress edit to commit, particularly for properties.

        Boolean fIsNew = m_le.IsNewFlight(); // hold onto this because we can change the status.
        if (fIsNew) {
            MFBLocation.GetMainLocation().setIsRecording(false);
            ShowRecordingIndicator();
        }

        // load any pending properties from the DB into the logbookentry object itself.
        m_le.SyncProperties();

        // load the telemetry string, if it's a first submission.
        if (m_le.IsNewFlight())
            m_le.szFlightData = MFBLocation.GetMainLocation().getFlightDataString();

        // Save for later if offline or if fForcePending
        boolean fIsOnline = MFBSoap.IsOnline(getContext());
        if (fForcePending || !fIsOnline) {

            // save the flight with id of -2 if it's a new flight
            if (fIsNew)
                m_le.idFlight = (fForcePending ? LogbookEntry.ID_QUEUED_FLIGHT_UNSUBMITTED : LogbookEntry.ID_PENDING_FLIGHT);

            // Existing flights can't be saved for later.  No good reason for that except work.
            if (m_le.IsExistingFlight()) {
                new AlertDialog.Builder(ActNewFlight.this.getActivity(), R.style.MFBDialog)
                        .setMessage(getString(R.string.errNoInternetNoSave))
                        .setTitle(getString(R.string.txtError))
                        .setNegativeButton(getString(R.string.lblOK), (d, id) -> {
                            d.cancel();
                            finish();
                        })
                        .create().show();
                return;
            }

            // now save it - but check for success
            if (!m_le.ToDB()) {
                // Failure!
                // Try saving it without the flight data string, in case that was the issue
                m_le.szFlightData = "";
                if (!m_le.ToDB()) {
                    // still didn't work. give an error message and, if necessary,
                    // restore to being a new flight.
                    MFBUtil.Alert(this, getString(R.string.txtError), m_le.szError);
                    // restore the previous idFlight if we were saving a new flight.
                    if (fIsNew) {
                        m_le.idFlight = LogbookEntry.ID_NEW_FLIGHT;
                        saveCurrentFlight();
                    }
                    return;
                }
                // if we're here, then phew - saved without the string
            }

            // if we're here, save was successful (even if flight data was dropped)
            RecentFlightsSvc.ClearCachedFlights();
            MFBMain.invalidateCachedTotals();
            if (fIsNew) {
                ResetFlight(true);
                MFBUtil.Alert(this, getString(R.string.txtSuccess), getString(R.string.txtSavedPendingFlight));
            } else {
                new AlertDialog.Builder(ActNewFlight.this.getActivity(), R.style.MFBDialog)
                        .setMessage(getString(R.string.txtSavedPendingFlight))
                        .setTitle(getString(R.string.txtSuccess))
                        .setNegativeButton("OK", (d, id) -> {
                            d.cancel();
                            finish();
                        })
                        .create().show();
            }
        } else {

            new SubmitTask(getContext(), this, m_le).execute();
        }
    }

    public void ToView() {
        if (getView() == null)
            return;

        SetStringForField(R.id.txtComments, m_le.szComments);
        SetStringForField(R.id.txtRoute, m_le.szRoute);

        SetIntForField(R.id.txtApproaches, m_le.cApproaches);
        SetIntForField(R.id.txtLandings, m_le.cLandings);
        SetIntForField(R.id.txtFSNightLandings, m_le.cNightLandings);
        SetIntForField(R.id.txtDayLandings, m_le.cFullStopLandings);

        SetCheckState(R.id.ckMyFlightbook, m_le.fPublic);
        SetCheckState(R.id.ckHold, m_le.fHold);

        SetLocalDateForField(R.id.btnFlightSet, m_le.dtFlight);

        // Engine/Flight dates
        SetUTCDateForField(R.id.btnEngineStartSet, m_le.dtEngineStart);
        SetUTCDateForField(R.id.btnEngineEndSet, m_le.dtEngineEnd);
        SetUTCDateForField(R.id.btnFlightStartSet, m_le.dtFlightStart);
        SetUTCDateForField(R.id.btnFlightEndSet, m_le.dtFlightEnd);
        SetDoubleForField(R.id.txtHobbsStart, m_le.hobbsStart);
        SetDoubleForField(R.id.txtHobbsEnd, m_le.hobbsEnd);

        SetDoubleForField(R.id.txtCFI, m_le.decCFI);
        SetDoubleForField(R.id.txtDual, m_le.decDual);
        SetDoubleForField(R.id.txtGround, m_le.decGrndSim);
        SetDoubleForField(R.id.txtIMC, m_le.decIMC);
        SetDoubleForField(R.id.txtNight, m_le.decNight);
        SetDoubleForField(R.id.txtPIC, m_le.decPIC);
        SetDoubleForField(R.id.txtSIC, m_le.decSIC);
        SetDoubleForField(R.id.txtSimIMC, m_le.decSimulatedIFR);
        SetDoubleForField(R.id.txtTotal, m_le.decTotal);
        SetDoubleForField(R.id.txtXC, m_le.decXC);

        boolean fIsSigned = (m_le.signatureStatus != LogbookEntry.SigStatus.None);
        findViewById(R.id.sectSignature).setVisibility(fIsSigned ? View.VISIBLE : View.GONE);
        findViewById(R.id.txtSignatureHeader).setVisibility(fIsSigned ? View.VISIBLE : View.GONE);
        if (fIsSigned)
        {
            ImageView imgSigStatus = (ImageView) findViewById(R.id.imgSigState);
            switch (m_le.signatureStatus) {
                case None:
                    break;
                case Valid:
                    imgSigStatus.setImageResource(R.drawable.sigok);
                    imgSigStatus.setContentDescription(getString(R.string.cdIsSignedValid));
                    ((TextView) findViewById(R.id.txtSigState)).setText(getString(R.string.cdIsSignedValid));
                    break;
                case Invalid:
                    imgSigStatus.setImageResource(R.drawable.siginvalid);
                    imgSigStatus.setContentDescription(getString(R.string.cdIsSignedInvalid));
                    ((TextView) findViewById(R.id.txtSigState)).setText(getString(R.string.cdIsSignedInvalid));
                    break;
            }

            String szSigInfo1 = String.format(Locale.getDefault(),
                    getString(R.string.lblSignatureTemplate1),
                    DateFormat.getDateFormat(getActivity()).format(m_le.signatureDate),
                    m_le.signatureCFIName);
            String szSigInfo2 = String.format(Locale.getDefault(),
                    getString(R.string.lblSignatureTemplate2),
                    m_le.signatureCFICert,
                    DateFormat.getDateFormat(getActivity()).format(m_le.signatureCFIExpiration));
            ((TextView) findViewById(R.id.txtSigInfo1)).setText(szSigInfo1);
            ((TextView) findViewById(R.id.txtSigInfo2)).setText(szSigInfo2);
            ((TextView) findViewById(R.id.txtSigComment)).setText(m_le.signatureComments);

            ImageView ivDigitizedSig = (ImageView) findViewById(R.id.imgDigitizedSig);
            if (m_le.signatureHasDigitizedSig)
            {
                if (ivDigitizedSig.getDrawable() == null) {
                    String szURL = String.format(Locale.US, "https://%s/Logbook/Public/ViewSig.aspx?id=%d", MFBConstants.szIP, m_le.idFlight);
                    GetDigitizedSigTask gdst = new GetDigitizedSigTask(ivDigitizedSig);
                    gdst.execute(szURL);
                }
            }
            else
                ivDigitizedSig.setVisibility(View.GONE);
        }

        // Aircraft spinner
        Aircraft[] rgSelectibleAircraft = SelectibleAircraft();
        if (rgSelectibleAircraft != null) {
            Spinner sp = (Spinner) findViewById(R.id.spnAircraft);
            for (int i = 0; i < rgSelectibleAircraft.length; i++) {
                if (m_le.idAircraft == rgSelectibleAircraft[i].AircraftID) {
                    sp.setSelection(i);
                    sp.setPrompt("Current Aircraft: " + rgSelectibleAircraft[i].TailNumber);
                    break;
                }
            }
        }

        // Current properties
        if (!m_le.IsExistingFlight() && m_le.rgCustomProperties == null)
            m_le.SyncProperties();
        setUpPropertiesForFlight();

        updateElapsedTime();
        updatePausePlayButtonState();
    }

    public void FromView() {
        if (getView() == null || m_le == null)
            return;

        // Integer fields
        m_le.cApproaches = IntFromField(R.id.txtApproaches);
        m_le.cFullStopLandings = IntFromField(R.id.txtDayLandings);
        m_le.cLandings = IntFromField(R.id.txtLandings);
        m_le.cNightLandings = IntFromField(R.id.txtFSNightLandings);

        // Double fields
        m_le.decCFI = DoubleFromField(R.id.txtCFI);
        m_le.decDual = DoubleFromField(R.id.txtDual);
        m_le.decGrndSim = DoubleFromField(R.id.txtGround);
        m_le.decIMC = DoubleFromField(R.id.txtIMC);
        m_le.decNight = DoubleFromField(R.id.txtNight);
        m_le.decPIC = DoubleFromField(R.id.txtPIC);
        m_le.decSIC = DoubleFromField(R.id.txtSIC);
        m_le.decSimulatedIFR = DoubleFromField(R.id.txtSimIMC);
        m_le.decTotal = DoubleFromField(R.id.txtTotal);
        m_le.decXC = DoubleFromField(R.id.txtXC);

        m_le.hobbsStart = DoubleFromField(R.id.txtHobbsStart);
        m_le.hobbsEnd = DoubleFromField(R.id.txtHobbsEnd);

        // Date - no-op because it should be in sync
        // Flight/Engine times - ditto

        // checkboxes
        m_le.fHold = CheckState(R.id.ckHold);
        m_le.fPublic = CheckState(R.id.ckMyFlightbook);

        // And strings
        m_le.szComments = StringFromField(R.id.txtComments);
        m_le.szRoute = StringFromField(R.id.txtRoute);

        // Aircraft spinner
        m_le.idAircraft = selectedAircraftID();
    }

    private int selectedAircraftID() {
        Aircraft[] rgSelectibleAircraft = SelectibleAircraft();
        if (rgSelectibleAircraft != null && rgSelectibleAircraft.length > 0) {
            Spinner sp = (Spinner) findViewById(R.id.spnAircraft);
            return ((Aircraft) sp.getSelectedItem()).AircraftID;
        }
        return -1;
    }

    private void AutoTotals() {
        double dtTotal = 0;

        FromView();

        // do autotime
        switch (MFBLocation.fPrefAutoFillTime) {
            case EngineTime:
                if (m_le.isKnownEngineTime())
                    dtTotal = (m_le.dtEngineEnd.getTime() - m_le.dtEngineStart.getTime() - totalTimePaused()) / MFBConstants.MS_PER_HOUR;
                break;
            case FlightTime:
                if (m_le.isKnownFlightTime())
                    dtTotal = (m_le.dtFlightEnd.getTime() - m_le.dtFlightStart.getTime() - totalTimePaused()) / MFBConstants.MS_PER_HOUR;
                break;
            case HobbsTime:
                // NOTE: we do NOT subtract totalTimePaused here because hobbs should already have subtracted pause time,
                // whether from being entered by user (hobbs on airplane pauses on ground or with engine stopped)
                // or from this being called by autohobbs (which has already subtracted it)
                if (m_le.hobbsStart > 0 && m_le.hobbsEnd > m_le.hobbsStart)
                    dtTotal = m_le.hobbsEnd - m_le.hobbsStart; // hobbs is already in hours
                break;
            case BlockTime: {
                long blockOut = 0;
                long blockIn = 0;
                if (m_le.rgCustomProperties != null) {
                    for (FlightProperty fp : m_le.rgCustomProperties) {
                        if (fp.idPropType == CustomPropertyType.idPropTypeBlockIn)
                            blockIn = MFBUtil.removeSeconds(fp.dateValue).getTime();
                        if (fp.idPropType == CustomPropertyType.idPropTypeBlockOut)
                            blockOut = MFBUtil.removeSeconds(fp.dateValue).getTime();
                    }
                    if (blockIn > 0 && blockOut > 0)
                        dtTotal = (blockIn - blockOut - totalTimePaused()) / MFBConstants.MS_PER_HOUR;
                }
            }
                break;
            case FlightStartToEngineEnd:
                if (m_le.isKnownFlightStart() && m_le.isKnownEngineEnd())
                    dtTotal = (m_le.dtEngineEnd.getTime() - m_le.dtFlightStart.getTime() - totalTimePaused()) / MFBConstants.MS_PER_HOUR;
                break;
            default:
                break;
        }

        if (dtTotal > 0) {
            boolean fIsReal = true;
            Spinner sp = (Spinner) findViewById(R.id.spnAircraft);

            if (MFBLocation.fPrefRoundNearestTenth)
                dtTotal = Math.round(dtTotal * 10.0) / 10.0;

            if (m_le.idAircraft > 0 && sp.getSelectedItem() != null)
                fIsReal = (((Aircraft) sp.getSelectedItem()).InstanceTypeID == 1);

            // update totals and XC if this is a real aircraft, else ground sim
            if (fIsReal) {
                m_le.decTotal = dtTotal;
                m_le.decXC = (Airport.MaxDistanceForRoute(m_le.szRoute) > MFBConstants.NM_FOR_CROSS_COUNTRY) ? dtTotal : 0.0;
            } else
                m_le.decGrndSim = dtTotal;

            ToView();
        }
    }

    private void AutoHobbs() {
        long dtHobbs = 0;
        long dtFlight = 0;
        long dtEngine = 0;

        FromView();

        // compute the flight time, in ms, if known
        if (m_le.isKnownFlightTime())
            dtFlight = m_le.dtFlightEnd.getTime() - m_le.dtFlightStart.getTime();

        // and engine time, if known.
        if (m_le.isKnownEngineTime())
            dtEngine = m_le.dtEngineEnd.getTime() - m_le.dtEngineStart.getTime();

        if (m_le.hobbsStart > 0) {
            switch (MFBLocation.fPrefAutoFillHobbs) {
                case EngineTime:
                    dtHobbs = dtEngine;
                    break;
                case FlightTime:
                    dtHobbs = dtFlight;
                    break;
                default:
                    break;
            }

            dtHobbs -= totalTimePaused();

            if (dtHobbs > 0) {
                m_le.hobbsEnd = m_le.hobbsStart + (dtHobbs / MFBConstants.MS_PER_HOUR);
                ToView(); // sync the view to the change we just made - especially since autototals can read it.

                // if total is linked to hobbs, need to do autotime too
                if (MFBLocation.fPrefAutoFillTime == MFBLocation.AutoFillOptions.HobbsTime)
                    AutoTotals();
            }
        }
    }

    private void resetDateOfFlight() {
        if (m_le != null && m_le.IsNewFlight()) {
            // set the date of the flight to now in local time.
            Date dt = new Date();
            if (m_le.isKnownEngineStart() && m_le.dtEngineStart.compareTo(dt) < 0)
                dt = m_le.dtEngineStart;
            if (m_le.isKnownFlightStart() && m_le.dtFlightStart.compareTo(dt) < 0)
                dt = m_le.dtFlightStart;
            m_le.dtFlight = dt;
            SetLocalDateForField(R.id.btnFlightSet, m_le.dtFlight);
        }
    }

    private void EngineStart() {
        // don't do any GPS stuff unless this is a new flight
        if (!m_le.IsNewFlight())
            return;

        resetDateOfFlight();

        if (MFBLocation.fPrefAutoDetect)
            AppendNearest();
        MFBLocation.GetMainLocation().setIsRecording(true); // will respect preference
        ShowRecordingIndicator();
        if (MFBConstants.fFakeGPS) {
            MFBLocation.GetMainLocation().stopListening(getContext());
            MFBLocation.GetMainLocation().setIsRecording(true); // will respect preference
            GPSSim gpss = new GPSSim(MFBLocation.GetMainLocation());
            gpss.FeedEvents();
        }
    }

    private void EngineStop() {
        // don't do any GPS stuff unless this is a new flight
        if (!m_le.IsNewFlight())
            return;

        if (MFBLocation.fPrefAutoDetect)
            AppendNearest();
        MFBLocation.GetMainLocation().setIsRecording(false);
        AutoHobbs();
        AutoTotals();
        ShowRecordingIndicator();
        unPauseFlight();
    }

    private void FlightStart() {
        // don't do any GPS stuff unless this is a new flight
        if (!m_le.IsNewFlight())
            return;

        if (!m_le.isKnownEngineStart())
            resetDateOfFlight();

        MFBLocation.GetMainLocation().setIsRecording(true);
        if (MFBLocation.fPrefAutoDetect)
            AppendNearest();
        unPauseFlight(); // don't pause in flight
    }

    private void FlightStop() {
        // don't do any GPS stuff unless this is a new flight
        if (!m_le.IsNewFlight())
            return;

        if (MFBLocation.fPrefAutoDetect)
            AppendNearest();
    }

    private void ShowRecordingIndicator() {
        imgRecording.setVisibility(MFBLocation.GetMainLocation() != null && MFBLocation.GetMainLocation().getIsRecording() ? View.VISIBLE : View.INVISIBLE);
    }

    private SimpleDateFormat dfSunriseSunset = null;

    public void UpdateStatus(MFBLocation.GPSQuality quality, Boolean fAirborne, Location loc,
                             Boolean fRecording) {
        if (!isAdded() || isDetached() || getActivity() == null)
            return;

        ShowRecordingIndicator();

        Resources res = getResources();

        int idSzQuality;

        switch (quality) {
            case Excellent:
                idSzQuality = R.string.lblGPSExcellent;
                break;
            case Good:
                idSzQuality = R.string.lblGPSGood;
                break;
            case Poor:
                idSzQuality = R.string.lblGPSPoor;
                break;
            default:
                idSzQuality = R.string.lblGPSUnknown;
                break;
        }

        if (loc != null) {
            txtQuality.setText(res.getString(idSzQuality));
            double Speed = loc.getSpeed() * MFBConstants.MPS_TO_KNOTS;
            txtSpeed.setText(Speed < 1.0 || !loc.hasSpeed() ? res.getString(R.string.lblNoSpeed) : String.format(Locale.getDefault(), "%.1fkts", Speed));
            txtAltitude.setText((loc.hasAltitude() ? String.format(Locale.getDefault(), "%d%s", (int) Math.round(loc.getAltitude() * MFBConstants.METERS_TO_FEET), getString(R.string.lblFeet)) : getString(R.string.lblNoAltitude)));
            double lat = loc.getLatitude();
            double lon = loc.getLongitude();
            txtLatitude.setText(String.format(Locale.getDefault(), "%.5f°%s", Math.abs(lat), lat > 0 ? "N" : "S"));
            txtLongitude.setText(String.format(Locale.getDefault(), "%.5f°%s", Math.abs(lon), lon > 0 ? "E" : "W"));
            SunriseSunsetTimes sst = new SunriseSunsetTimes(new Date(loc.getTime()), lat, lon);
            if (dfSunriseSunset == null)
                dfSunriseSunset = new SimpleDateFormat("hh:mm a z", Locale.getDefault());

            txtSunrise.setText(dfSunriseSunset.format(sst.Sunrise));
            txtSunset.setText(dfSunriseSunset.format(sst.Sunset));
        }

        // don't show in-air/on-ground if we aren't actually detecting these
        if (MFBLocation.fPrefAutoDetect)
            txtStatus.setText(res.getString(fAirborne ? R.string.lblFlightInAir : R.string.lblFlightOnGround));
        else
            txtStatus.setText(res.getString(R.string.lblGPSUnknown));

        if (fAirborne)
            unPauseFlight();
    }

    // Pause/play functionality
    private long timeSinceLastPaused() {
        if (ActNewFlight.fPaused)
            return (new Date()).getTime() - ActNewFlight.dtTimeOfLastPause;
        else
            return 0;
    }

    private long totalTimePaused() {
        return ActNewFlight.dtPauseTime + timeSinceLastPaused();
    }

    private void pauseFlight() {
        ActNewFlight.dtTimeOfLastPause = (new Date()).getTime();
        ActNewFlight.fPaused = true;
    }

    public void unPauseFlight() {
        if (ActNewFlight.fPaused) {
            ActNewFlight.dtPauseTime += timeSinceLastPaused();
            ActNewFlight.fPaused = false; // do this AFTER calling [self timeSinceLastPaused]
        }
    }

    public void togglePausePlay() {
        if (fPaused)
            unPauseFlight();
        else
            pauseFlight();
        updatePausePlayButtonState();
    }

    public void startEngine() {
        if (m_le != null && !m_le.isKnownEngineStart()) {
            m_le.dtEngineStart = MFBUtil.nowWith0Seconds();
            EngineStart();
            ToView();
        }
    }

    public void stopEngine() {
        if (m_le != null && !m_le.isKnownEngineEnd()) {
            m_le.dtEngineEnd = MFBUtil.nowWith0Seconds();
            EngineStop();
            ToView();
        }
    }

    private void updateElapsedTime() {            // update the button state
        ImageButton ib = (ImageButton) findViewById(R.id.btnPausePlay);
        // pause/play should only be visible on ground with engine running (or flight start known but engine end unknown)
        boolean fShowPausePlay = !MFBLocation.IsFlying && (m_le.isKnownEngineStart() || m_le.isKnownFlightStart()) && !m_le.isKnownEngineEnd();
        ib.setVisibility(fShowPausePlay ? View.VISIBLE : View.INVISIBLE);

        TextView txtElapsed = (TextView) findViewById(R.id.txtElapsedTime);

        if (txtElapsed != null) {
            long dtTotal;
            long dtFlight = 0;
            long dtEngine = 0;

            Boolean fIsKnownFlightStart = m_le.isKnownFlightStart();
            Boolean fIsKnownEngineStart = m_le.isKnownEngineStart();

            if (fIsKnownFlightStart) {
                if (!m_le.isKnownFlightEnd()) // in flight
                    dtFlight = (new Date()).getTime() - m_le.dtFlightStart.getTime();
                else
                    dtFlight = m_le.dtFlightEnd.getTime() - m_le.dtFlightStart.getTime();
            }

            if (fIsKnownEngineStart) {
                if (!m_le.isKnownEngineEnd())
                    dtEngine = (new Date()).getTime() - m_le.dtEngineStart.getTime();
                else
                    dtEngine = m_le.dtEngineEnd.getTime() - m_le.dtEngineStart.getTime();
            }

            // if totals mode is FLIGHT TIME, then elapsed time is based on flight time if/when it is known.
            // OTHERWISE, we use engine time (if known) or else flight time.
            if (MFBLocation.fPrefAutoFillTime == MFBLocation.AutoFillOptions.FlightTime)
                dtTotal = fIsKnownFlightStart ? dtFlight : 0;
            else
                dtTotal = fIsKnownEngineStart ? dtEngine : dtFlight;

            dtTotal -= this.totalTimePaused();
            if (dtTotal <= 0)
                dtTotal = 0; // should never happen

            // dtTotal is in milliseconds - convert it to seconds for ease of reading
            dtTotal /= 1000.0;
            String sTime = String.format(Locale.US, "%02d:%02d:%02d", (int) (dtTotal / 3600), ((((int) dtTotal) % 3600) / 60), ((int) dtTotal) % 60);
            txtElapsed.setText(sTime);
        }

        ShowRecordingIndicator();
    }

    private void updatePausePlayButtonState() {
        // update the button state
        ImageButton ib = (ImageButton) findViewById(R.id.btnPausePlay);
        ib.setImageResource(ActNewFlight.fPaused ? R.drawable.play : R.drawable.pause);
    }

    private void toggleFlightPause() {
        // don't pause or play if we're not flying/engine started
        if (m_le.isKnownFlightStart() || m_le.isKnownEngineStart()) {
            if (ActNewFlight.fPaused)
                unPauseFlight();
            else
                pauseFlight();
        } else {
            unPauseFlight();
            ActNewFlight.dtPauseTime = 0;
        }

        updateElapsedTime();
        updatePausePlayButtonState();
    }

    /*
     * (non-Javadoc)
     * @see com.myflightbook.android.ActMFBForm.GallerySource#getGalleryID()
     * GallerySource protocol
     */
    public int getGalleryID() {
        return R.id.tblImageTable;
    }

    public View getGalleryHeader() {
        return findViewById(R.id.txtImageHeader);
    }

    public MFBImageInfo[] getImages() {
        if (m_le == null || m_le.rgFlightImages == null)
            return new MFBImageInfo[0];
        else
            return m_le.rgFlightImages;
    }

    public void setImages(MFBImageInfo[] rgmfbii) {
        if (m_le == null) {
            throw new NullPointerException("m_le is null in setImages");
        }
        m_le.rgFlightImages = rgmfbii;
    }

    public void newImage(MFBImageInfo mfbii) {
        Log.w(MFBConstants.LOG_TAG, String.format("newImage called. m_le is %s", m_le == null ? "null" : "not null"));
        m_le.AddImageForflight(mfbii);
    }

    public void refreshGallery() {
        setUpGalleryForFlight();
    }

    public PictureDestination getDestination() {
        return PictureDestination.FlightImage;
    }

    public void invalidate() {
        ResetFlight(false);
    }

    private void UpdateIfChanged(int id, int value) {
        if (IntFromField(id) != value)
            SetIntForField(id, value);
    }

    // Update the fields which could possibly have changed via auto-detect
    public void RefreshDetectedFields() {
        SetUTCDateForField(R.id.btnFlightStartSet, m_le.dtFlightStart);
        SetUTCDateForField(R.id.btnFlightEndSet, m_le.dtFlightEnd);

        UpdateIfChanged(R.id.txtLandings, m_le.cLandings);
        UpdateIfChanged(R.id.txtFSNightLandings, m_le.cNightLandings);
        UpdateIfChanged(R.id.txtDayLandings, m_le.cFullStopLandings);
        SetDoubleForField(R.id.txtNight, m_le.decNight);

        SetStringForField(R.id.txtRoute, m_le.szRoute);
    }

    private void setUpPropertiesForFlight() {
        Activity a = getActivity();
        if (a == null)
            return;
        LayoutInflater l = a.getLayoutInflater();
        TableLayout tl = (TableLayout) findViewById(R.id.tblPinnedProperties);
        if (tl == null)
            return;
        tl.removeAllViews();

        if (m_le == null)
            return;

        m_le.SyncProperties();

        if (m_le.rgCustomProperties == null)
            return;

        HashSet<Integer> pinnedProps = CustomPropertyType.getPinnedProperties(getActivity().getSharedPreferences(CustomPropertyType.prefSharedPinnedProps, Activity.MODE_PRIVATE));
        HashSet<Integer> templateProps = PropertyTemplate.mergeTemplates(m_activeTemplates.toArray(new PropertyTemplate[0]));

        CustomPropertyType[] rgcptAll = CustomPropertyTypesSvc.getCachedPropertyTypes();
        if (rgcptAll == null)
            return;

        Arrays.sort(rgcptAll);

        FlightProperty[] rgProps = FlightProperty.CrossProduct(m_le.rgCustomProperties, rgcptAll);

        for (FlightProperty fp : rgProps) {
            // should never happen, but does - not sure why
            if (fp == null)
                continue;

            if (fp.CustomPropertyType() == null)
                fp.RefreshPropType();

            Boolean fIsPinned = CustomPropertyType.isPinnedProperty(pinnedProps, fp.idPropType);


            if (!fIsPinned && !templateProps.contains(fp.idPropType) && fp.IsDefaultValue())
                continue;

            TableRow tr = (TableRow) l.inflate(R.layout.cpttableitem, tl, false);
            tr.setId(View.generateViewId());

            PropertyEdit pe = tr.findViewById(R.id.propEdit);
            pe.InitForProperty(fp, tr.getId(), this, (fp.CustomPropertyType().idPropType == CustomPropertyType.idPropTypeTachStart) ? (CrossFillDelegate) sender -> {
                Double d = Aircraft.getHighWaterTachForAircraft(selectedAircraftID());
                if (d > 0)
                    sender.setDoubleValue(d);
            } : this);

            tr.findViewById(R.id.imgFavorite).setVisibility(fIsPinned ? View.VISIBLE : View.INVISIBLE);

            tl.addView(tr, new TableLayout.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT));

        }
    }

    //region in-line property editing
    private static class DeletePropertyTask extends AsyncTask<Void, Void, Boolean> {
        private ProgressDialog m_pd = null;
        private final FlightProperty m_fp;
        private final AsyncWeakContext<ActNewFlight> m_ctxt;

        DeletePropertyTask(Context c, ActNewFlight act, FlightProperty fp) {
            super();
            m_ctxt = new AsyncWeakContext<>(c, act);
            m_fp = fp;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            FlightPropertiesSvc fpsvc = new FlightPropertiesSvc();
            fpsvc.DeletePropertyForFlight(AuthToken.m_szAuthToken, m_fp.idFlight, m_fp.idProp, m_ctxt.getContext());
            return true;
        }

        protected void onPreExecute() {
            m_pd = MFBUtil.ShowProgress(m_ctxt.getCallingActivity(), m_ctxt.getContext().getString(R.string.prgDeleteProp));
        }

        protected void onPostExecute(Boolean b) {
            try {
                if (m_pd != null)
                    m_pd.dismiss();
            } catch (Exception e) {
                Log.e(MFBConstants.LOG_TAG, Log.getStackTraceString(e));
            }

            ActNewFlight act = m_ctxt.getCallingActivity();
            if (act != null)
                act.setUpPropertiesForFlight();
        }
    }

    private void deleteDefaultedProperty(FlightProperty fp) {
        if (fp.idProp > 0 && fp.IsDefaultValue()) {
            new DeletePropertyTask(getContext(), this, fp).execute();
            return;
        }

        // Otherwise, save it
        FlightProperty[] rgProps = FlightProperty.CrossProduct(m_le.rgCustomProperties, CustomPropertyTypesSvc.getCachedPropertyTypes());
        FlightProperty[] rgfpUpdated = FlightProperty.DistillList(rgProps);
        FlightProperty.RewritePropertiesForFlight(m_le.idLocalDB, rgfpUpdated);

    }

    @Override
    public void updateProperty(int id, FlightProperty fp) {
        if (fp == null || m_le == null) // this can get called by PropertyEdit as it loses focus.
            return;

        FlightProperty[] rgProps = FlightProperty.CrossProduct(m_le.rgCustomProperties, CustomPropertyTypesSvc.getCachedPropertyTypes());
        for (int i = 0; i < rgProps.length; i++) {
            if (rgProps[i].idPropType == fp.idPropType) {
                rgProps[i] = fp;
                deleteDefaultedProperty(fp);
                FlightProperty.RewritePropertiesForFlight(m_le.idLocalDB, FlightProperty.DistillList(rgProps));
                m_le.SyncProperties();
                break;
            }
        }

        if (MFBLocation.fPrefAutoFillTime == MFBLocation.AutoFillOptions.BlockTime &&
                (fp.idPropType == CustomPropertyType.idPropTypeBlockOut || fp.idPropType == CustomPropertyType.idPropTypeBlockIn))
            AutoTotals();
    }
    //endregion

    // region Templates
    private void updateTemplatesForAircraft(boolean noDefault) {
        Activity a = getActivity();
        if (a == null)
            return;
        if (PropertyTemplate.sharedTemplates == null && PropertyTemplate.getSharedTemplates(a.getSharedPreferences(PropertyTemplate.PREF_KEY_TEMPLATES, Activity.MODE_PRIVATE)) == null)
            return;

        PropertyTemplate[] rgDefault = PropertyTemplate.getDefaultTemplates();
        PropertyTemplate ptAnon = PropertyTemplate.getAnonTemplate();
        PropertyTemplate ptSim = PropertyTemplate.getSimTemplate();

        Aircraft ac = Aircraft.getAircraftById(m_le.idAircraft, m_rgac);
        if (ac != null && ac.DefaultTemplates.size() > 0)
            m_activeTemplates.addAll(Arrays.asList(PropertyTemplate.templatesWithIDs(ac.DefaultTemplates)));
        else if (rgDefault.length > 0 && !noDefault)
            m_activeTemplates.addAll(Arrays.asList(rgDefault));

        m_activeTemplates.remove(ptAnon);
        m_activeTemplates.remove(ptSim);

        // Always add in sims or anon as appropriate
        if (ac != null) {
            if (ac.IsAnonymous() && ptAnon != null)
                m_activeTemplates.add(ptAnon);
            if (!ac.IsReal() && ptSim != null)
                m_activeTemplates.add(ptSim);
        }
    }
    // endregion
}
