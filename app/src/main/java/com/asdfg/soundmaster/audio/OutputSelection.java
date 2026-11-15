package com.asdfg.soundmaster.audio;

import java.util.ArrayList;
import java.util.List;

/** Audio port IDs are temporary and can be recycled during Bluetooth codec negotiation. */
public final class OutputSelection {
    public static final class Endpoint {
        public final int id, type;
        public final String address, name;
        public Endpoint(int id, int type, String address, String name) {
            this.id=id; this.type=type;
            this.address=address == null ? "" : address;
            this.name=name == null ? "" : name;
        }
    }
    public static Endpoint resolve(List<Endpoint> connected, int requestedId, int type,
            String address, String name) {
        List<Endpoint> matches=new ArrayList<>();
        boolean identity=type > 0;
        boolean usableAddress=address != null && !address.isEmpty()
                && !"02:00:00:00:00:00".equalsIgnoreCase(address);
        for (Endpoint device: connected) {
            boolean match;
            if (!identity) match=device.id == requestedId;
            else match=device.type == type && (usableAddress
                    ? device.address.equalsIgnoreCase(address) : !device.name.isEmpty() && device.name.equals(name));
            if (match) matches.add(device);
        }
        if (matches.size()>1) throw new IllegalStateException("Ambiguous connected Bluetooth identity; disconnect duplicate outputs and select again");
        return matches.isEmpty() ? null : matches.get(0);
    }
    private OutputSelection() {}
}
