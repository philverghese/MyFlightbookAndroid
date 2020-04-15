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

package Model;

import android.content.Context;
import android.net.Uri;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Created by ericberman on 11/30/17.
 *
 * Concrete telemetry class that can read/write GPX
 */

public class KML extends Telemetry {

    KML(Uri uri, Context c) {
        super(uri, c);
    }

    private void readCoord(LocSample sample, XmlPullParser parser) throws IOException, XmlPullParserException {
        if (sample == null)
            return;
        String s = readText(parser);
        String[] rg = s.split(" ");
        if (rg.length > 1) {
            sample.Longitude = Double.parseDouble(rg[0]);
            sample.Latitude = Double.parseDouble(rg[1]);
            if (rg.length > 2)
                sample.Alt = (int) (Double.parseDouble(rg[2]) * MFBConstants.METERS_TO_FEET);
        }
    }

    @Override
    public LocSample[] readFeed(XmlPullParser parser) {
        ArrayList<LocSample> lst = new ArrayList<>();

        LocSample sample = null;
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                try {
                    int eventType = parser.getEventType();
                    if (eventType == XmlPullParser.START_TAG) {
                        String name = parser.getName();
                        // Starts by looking for the entry tag
                        switch (name) {
                            case "when":
                                Date dt = ParseUTCDate(readText(parser));

                                sample = new LocSample(0, 0, 0, 0, 1.0, "");
                                sample.TimeStamp = dt;
                                break;
                            case "gx:coord":
                                if (sample != null) {
                                    readCoord(sample, parser);
                                    lst.add(sample);
                                    sample = null;
                                }
                                break;
                        }
                    } else if (eventType == XmlPullParser.END_TAG) {
                        if (sample != null && parser.getName().equals("trkpt")) {
                            lst.add(sample);
                            sample = null;
                        }
                    }
                }
                catch (XmlPullParserException ignored) { }
            }
        } catch (XmlPullParserException | IOException e) {
            e.printStackTrace();
        }

        return ComputeSpeed(lst.toArray(new LocSample[0]));
    }

    @SuppressWarnings("unused")
    public static String getFlightDataStringAsKML(LatLong[] rgloc) {

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault());
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

        StringBuilder sb = new StringBuilder();
        // Hack - this is brute force writing, not proper generation of XML.  But it works...
        // We are also assuming valid timestamps (i.e., we're using gx:Track)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\">\r\n");
        sb.append("<Document>\r\n<Style id=\"redPoly\"><LineStyle><color>7f0000ff</color><width>4</width></LineStyle><PolyStyle><color>7f0000ff</color></PolyStyle></Style>\r\n");
        sb.append("<open>1</open>\r\n<visibility>1</visibility>\r\n<Placemark>\r\n\r\n<styleUrl>#redPoly</styleUrl><gx:Track>\r\n");

        if (rgloc.length > 0 && rgloc[0] instanceof LocSample) {
            sb.append("<extrude>1</extrude>\r\n<altitudeMode>absolute</altitudeMode>\r\n");
            for (LatLong ll : rgloc) {
                LocSample l = (LocSample) ll;
                sb.append(String.format(Locale.US, "<when>%s</when>\r\n<gx:coord>%.8f %.8f %.1f</gx:coord>\r\n",
                        sdf.format(l.TimeStamp),
                        l.Longitude,
                        l.Latitude,
                        l.Alt / MFBConstants.METERS_TO_FEET
                ));
            }
        } else {
            sb.append("<altitudeMode>clampToGround</altitudeMode>\r\n");
            for (LatLong ll : rgloc) {
                sb.append(String.format(Locale.US, "<gx:coord>%.8f %.8f 0</gx:coord>\r\n",
                        ll.Longitude,
                        ll.Latitude
                ));
            }
        }
        sb.append("</gx:Track></Placemark></Document></kml>");
        return sb.toString();
    }
}
