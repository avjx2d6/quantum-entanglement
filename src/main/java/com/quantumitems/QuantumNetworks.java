package com.quantumitems;

import com.mojang.serialization.DataResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Persistent authority for all quantum networks. The pool stored here is the
 * single source of truth for item counts; linked stacks are mere windows.
 * Always loaded (attached to the overworld), independent of chunk state.
 */
public class QuantumNetworks extends SavedData {
    public static final String ID = QuantumItemsMod.MOD_ID + "_networks";
    public static final int MAX_MEMBERS = 4;

    private final Map<Integer, Network> networks = new HashMap<>();
    private int nextNetworkId = 1;

    public static class Network {
        public int pool;
        public final SortedSet<Integer> aliveMembers = new TreeSet<>();
        public int nextMemberId;
        public Item item;
        public DataComponentPatch snapshot = DataComponentPatch.EMPTY;
    }

    public static QuantumNetworks get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(QuantumNetworks::new, QuantumNetworks::load), ID);
    }

    @Nullable
    public Network network(int networkId) {
        return networks.get(networkId);
    }

    /**
     * Creates a network from a plain stack. The snapshot captures the stack's
     * components as they are at entanglement time; any later divergence from
     * it collapses the network.
     *
     * @return the new network id; members #1 and #2 are allocated
     */
    public int createNetwork(ItemStack plainStack) {
        int id = nextNetworkId++;
        Network network = new Network();
        network.pool = plainStack.getCount();
        network.item = plainStack.getItem();
        network.snapshot = plainStack.getComponentsPatch();
        network.aliveMembers.add(1);
        network.aliveMembers.add(2);
        network.nextMemberId = 3;
        networks.put(id, network);
        setDirty();
        return id;
    }

    /** Removes a dissolved network. */
    public void removeNetwork(int networkId) {
        if (networks.remove(networkId) != null) {
            setDirty();
        }
    }

    /**
     * Allocates one more window in an existing network.
     *
     * @return the new member id, or -1 if the network is missing or full
     */
    public int addMember(int networkId) {
        Network network = networks.get(networkId);
        if (network == null || network.aliveMembers.size() >= MAX_MEMBERS) {
            return -1;
        }
        int member = network.nextMemberId++;
        network.aliveMembers.add(member);
        setDirty();
        return member;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        ListTag list = new ListTag();
        for (Map.Entry<Integer, Network> entry : networks.entrySet()) {
            Network network = entry.getValue();
            CompoundTag networkTag = new CompoundTag();
            networkTag.putInt("id", entry.getKey());
            networkTag.putInt("pool", network.pool);
            networkTag.putIntArray("members", network.aliveMembers.stream().mapToInt(Integer::intValue).toArray());
            networkTag.putInt("next_member", network.nextMemberId);
            networkTag.putString("item", BuiltInRegistries.ITEM.getKey(network.item).toString());
            DataComponentPatch.CODEC.encodeStart(ops, network.snapshot).result()
                    .ifPresent(snapshotTag -> networkTag.put("snapshot", snapshotTag));
            list.add(networkTag);
        }
        tag.put("networks", list);
        tag.putInt("next_network", nextNetworkId);
        return tag;
    }

    public static QuantumNetworks load(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        QuantumNetworks data = new QuantumNetworks();
        data.nextNetworkId = tag.getInt("next_network");
        for (Tag element : tag.getList("networks", Tag.TAG_COMPOUND)) {
            CompoundTag networkTag = (CompoundTag) element;
            Network network = new Network();
            network.pool = networkTag.getInt("pool");
            for (int member : networkTag.getIntArray("members")) {
                network.aliveMembers.add(member);
            }
            network.nextMemberId = networkTag.getInt("next_member");
            network.item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(networkTag.getString("item")));
            if (networkTag.contains("snapshot")) {
                DataResult<DataComponentPatch> parsed = DataComponentPatch.CODEC.parse(ops, networkTag.get("snapshot"));
                network.snapshot = parsed.result().orElse(DataComponentPatch.EMPTY);
            }
            data.networks.put(networkTag.getInt("id"), network);
        }
        return data;
    }
}
