package com.mtrstar.lock.client;

import com.mtrstar.lock.network.OwnershipSync;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MtrlockClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(OwnershipSync.CHANNEL,
                (client, handler, buf, responseSender) -> {
                    final OwnershipSync.Snapshot snapshot = OwnershipSync.read(buf);

                    final Map<String, Set<String>> teamMembers = new HashMap<>();
                    final Map<String, String> teamNames = new HashMap<>();
                    for (var e : snapshot.teams().entrySet()) {
                        teamMembers.put(e.getKey(), e.getValue().members());
                        teamNames.put(e.getKey(), e.getValue().name());
                    }

                    client.execute(() -> {
                        ClientOwnership.setAll(snapshot.ownership());
                        ClientOwnership.setOperator(snapshot.operator());
                        ClientOwnership.setShareSnapshot(snapshot.shares(), teamMembers);
                        ClientOwnership.setTeamNames(teamNames);
                    });
                });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientOwnership.clear());
    }
}
