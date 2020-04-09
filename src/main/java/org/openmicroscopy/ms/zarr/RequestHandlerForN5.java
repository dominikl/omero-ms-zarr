/*
 * Copyright (C) 2018 University of Dundee & Open Microscopy Environment.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.openmicroscopy.ms.zarr;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.apache.commons.codec.binary.Base64;
import org.openmicroscopy.util.N5Provider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;


public class RequestHandlerForN5 implements Handler<RoutingContext> {

    private final Connection connection;

    private N5Provider prov;

    public RequestHandlerForN5(Connection connection) {
        this.connection = connection;
        Properties p = System.getProperties();
        prov = new N5Provider(p.getProperty("bf2raw.dir"),p.getProperty("omero.dir"),p.getProperty("n5.dir"));
    }

    /**
     * Add this as both GET and POST handler for the given path.
     * @param router the router for which this can handle requests
     * @param path the path on which those requests come
     */
    public void handleFor(Router router, String path) {
        router.get(path).handler(this);
        router.post(path).handler(BodyHandler.create()).handler(this);
    }

    /**
     * Construct a HTTP failure response.
     * @param response the HTTP response that is to bear the failure
     * @param code the HTTP response code
     * @param message a message that describes the failure
     */
    private static void fail(HttpServerResponse response, int code, String message) {
        response.setStatusCode(code);
        response.setStatusMessage(message);
        response.end();
    }

    /**
     * Handle incoming requests that query OMERO system user and group IDs.
     * @param context the routing context
     */
    @Override
    public void handle(RoutingContext context) {
        final HttpServerRequest request = context.request();
        final HttpServerResponse response = request.response();
        /* get role and type from query */
        long imageId = -1;
        int res = -0;
        int index =  0;
        int x = 0;
        int y = 0;
        int w = 0;
        int h = 0;
        switch (request.method()) {
            case GET:
                try {
                    imageId = Long.parseLong(request.getParam("imageid"));
                    if (request.getParam("res") != null)
                        res = Integer.parseInt(request.getParam("res"));
                    if (request.getParam("index") != null)
                        index = Integer.parseInt(request.getParam("index"));
                    if (request.getParam("x") != null)
                        x = Integer.parseInt(request.getParam("x"));
                    if (request.getParam("y") != null)
                        y = Integer.parseInt(request.getParam("y"));
                    if (request.getParam("w") != null)
                        w = Integer.parseInt(request.getParam("w"));
                    if (request.getParam("h") != null)
                        h = Integer.parseInt(request.getParam("h"));
                } catch (Exception e) {
                    fail(response, 404, "no valid image id provided");
                }
                break;
            default:
                fail(response, 404, "unknown path for this method");
                return;
        }

        final String query = "select o.path, o.name from image i, " +
                             "fileset f, filesetentry fe, originalfile o " +
                             "where i.fileset = f.id " +
                             "and f.id = fe.fileset " +
                             "and fe.originalfile = o.id " +
                             "and i.id = %d;";
        String imgPath = "";
        try (final Statement statement = connection.createStatement()) {
            final ResultSet rows = statement.executeQuery(String.format(query, imageId));
            if (rows.next()) {
                imgPath = rows.getString(1)+"/"+rows.getString(2);
            } else {
                fail(response, 500, "database query returned no data");
                return;
            }
        } catch (SQLException sqle) {
            fail(response, 500, "database query failed");
            return;
        }


        byte[] result = new byte[0];
        try {
            result = prov.getPlane(imgPath, res, index,x, y, w, h);
        } catch (Exception e) {
            e.printStackTrace();
        }

        final JsonObject responseJson = new JsonObject(ImmutableMap.of("data", Base64.encodeBase64String(result)));
        final String responseText = responseJson.toString();
        response.putHeader("Content-Type", "application/json; charset=utf-8");
        response.putHeader("Content-Length", Integer.toString(responseText.length()));
        response.end(responseText);
    }
}
