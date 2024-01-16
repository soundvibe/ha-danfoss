/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package net.soundvibe.hasio.danfoss.protocol.config;

import org.eclipse.jdt.annotation.NonNull;

public class DominionConfiguration {
    public static class Request {
        public final String phoneName;
        public final String phonePublicKey;
        public final boolean chunkedMessage;

        public Request(String userName, @NonNull String peerId) {
            phoneName = userName;
            phonePublicKey = peerId;
            // chunkedMessage is important because if the data is too long, it wouldn't fit into
            // fixed length buffer (approx. 1536 bytes) of phone's mdglib version. See comments
            // in DeviSmartConfigConnection for more insight on this.
            chunkedMessage = true;
        }
    }

    /*
     * Configuration description is a JSON of the following self-explanatory format.
     * @formatter:off
     * Example from DeviSmart:
     * {
     *   "houseName":"My Flat",
     *   "houseEditUsers":false,
     *   "rooms":[
     *      {
     *        "roomName":"Living room",
     *        "peerId":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
     *        "zone":"Living",
     *        "sortOrder":0
     *      },
     *      {
     *        "roomName":"Kitchen",
     *        "peerId":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
     *        "zone":"None",
     *        "sortOrder":1
     *      }
     *   ]
     * }
     * Example from Icon:
     * {
     *   "houseName":"MyHouse",
     *   "housePeerId":" xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx ",
     *   "houseEditUsers":false
     * }
     * @formatter:on
     * "houseEditUsers", "zone" and "sortOrder" are used by the smartphone app only. Thermostats
     * are not aware of them.
     */
    public static class Response {
        public String houseName;
        public String housePeerId;
        public boolean houseEditUsers;
        public Room[] rooms;
    }

    public static class Room {
        public String roomName;
        public String peerId;
        public String zone;
        public int sortOrder;
    }
}
