/*
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301,
 * USA.
 */
package org.anjocaido.groupmanager.permissions;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;

import org.anjocaido.groupmanager.GroupManager;
import org.anjocaido.groupmanager.data.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.PluginManager;

/**
 *
 * BukkitPermissions overrides to force GM reponses to Superperms
 *
 * @author ElgarL
 */
public class BukkitPermissions {

    protected WeakHashMap<String, PermissionAttachment> attachments = new WeakHashMap<>();
    protected LinkedHashMap<String, Permission> registeredPermissions = new LinkedHashMap<>();
    protected GroupManager plugin;
    protected boolean dumpAllPermissions = true;
    protected boolean dumpMatchedPermissions = true;
    private boolean player_join = false;

    /**
     * @return the player_join
     */
    public boolean isPlayer_join() {

        return player_join;
    }

    /**
     * @param player_join the player_join to set
     */
    public void setPlayer_join(boolean player_join) {

        this.player_join = player_join;
    }

    private static Field permissions;

    // Setup reflection (Thanks to Codename_B for the reflection source)
    static {
        try {
            permissions = PermissionAttachment.class.getDeclaredField("permissions");
            permissions.setAccessible(true);
        } catch (SecurityException | NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    public BukkitPermissions(GroupManager plugin) {

        this.plugin = plugin;
        this.reset();
        this.registerEvents();

        GroupManager.logger.info("Superperms support enabled.");
    }

    public void reset() {

        /*
         * collect new permissions
         * and register all attachments.
         */
        this.collectPermissions();
        this.updateAllPlayers();
    }

    private void registerEvents() {

        PluginManager manager = plugin.getServer().getPluginManager();

        manager.registerEvents(new PlayerEvents(), plugin);
        manager.registerEvents(new BukkitEvents(), plugin);
    }

    public void collectPermissions() {

        registeredPermissions.clear();

        Bukkit.getPluginManager().getPermissions().forEach((perm) -> registeredPermissions.put(perm.getName().toLowerCase(), perm));
    }

    public void updatePermissions(Player player) {

        this.updatePermissions(player, null);
    }

    /**
     * Push all permissions which are registered with GM for this player, on
     * this world to Bukkit and make it update for the child nodes.
     *
     * @param player
     * @param world
     */
    public void updatePermissions(Player player, String world) {

        if (player == null || !GroupManager.isLoaded()) {
            return;
        }

        String name = player.getName();

        // Reset the User objects player reference.
        User user = plugin.getWorldsHolder().getWorldData(player.getWorld().getName()).getUser(name);
        if (user != null) {
            user.updatePlayer(player);
        }

        PermissionAttachment attachment;

        // Find the players current attachment, or add a new one.
        if (this.attachments.containsKey(name)) {
            attachment = this.attachments.get(name);
        } else {
            attachment = player.addAttachment(plugin);
            this.attachments.put(name, attachment);
        }

        if (world == null) {
            world = player.getWorld().getName();
        }

		// Add all permissions for this player (GM only)
        // child nodes will be calculated by Bukkit.
        List<String> playerPermArray = new ArrayList<>(plugin.getWorldsHolder().getWorldData(world).getPermissionsHandler().getAllPlayersPermissions(name, false));
        LinkedHashMap<String, Boolean> newPerms = new LinkedHashMap<>();

		// Sort the perm list by parent/child, so it will push to superperms
        // correctly.
        playerPermArray = sort(playerPermArray);

        Boolean value = false;
        for (String permission : playerPermArray) {
            value = (!permission.startsWith("-"));
            newPerms.put((value ? permission : permission.substring(1)), value);
        }

        /*
         * Do not push any perms to bukkit if...
         * We are in offline mode
         * and the player has the 'groupmanager.noofflineperms' permission.
         */
        if (!Bukkit.getServer().getOnlineMode()
                && (newPerms.containsKey("groupmanager.noofflineperms") && newPerms.get("groupmanager.noofflineperms"))) {
            removeAttachment(name);
            return;
        }

        /**
         * This is put in place until such a time as Bukkit pull 466 is
         * implemented https://github.com/Bukkit/Bukkit/pull/466
         */
        try { // Codename_B source
            synchronized (attachment.getPermissible()) {

                @SuppressWarnings("unchecked")
                Map<String, Boolean> orig = (Map<String, Boolean>) permissions.get(attachment);
				// Clear the map (faster than removing the attachment and
                // recalculating)
                orig.clear();
                // Then whack our map into there
                orig.putAll(newPerms);
                // That's all folks!
                attachment.getPermissible().recalculatePermissions();

            }
        } catch (IllegalArgumentException | IllegalAccessException e) {
            e.printStackTrace();
        }

        GroupManager.logger.log(Level.FINEST, "Attachment updated for: {0}", name);
    }

    /**
     * Sort a permission node list by parent/child
     *
     * @param permList
     * @return List sorted for priority
     */
    private List<String> sort(List<String> permList) {

        List<String> result = new ArrayList<>();

        /*
         * Ignore stupid plugins which add empty permission nodes.
         */// Insert the parent node before the child
        permList.stream().filter(key -> !key.isEmpty()).forEach(key -> {
            String a = key.charAt(0) == '-' ? key.substring(1) : key;
            Map<String, Boolean> allchildren = GroupManager.BukkitPermissions.getAllChildren(a, new HashSet<>());
            if (allchildren != null) {

                ListIterator<String> itr = result.listIterator();

                while (itr.hasNext()) {
                    String node = itr.next();
                    String b = node.charAt(0) == '-' ? node.substring(1) : node;

                    // Insert the parent node before the child
                    if (allchildren.containsKey(b)) {
                        itr.set(key);
                        itr.add(node);
                        break;
                    }
                }
            }
            if (!result.contains(key)) {
                result.add(key);
            }
        });

        return result;
    }

    /**
     * Fetch all permissions which are registered with superperms. {can include
     * child nodes)
     *
     * @param includeChildren
     * @return List of all permission nodes
     */
    public List<String> getAllRegisteredPermissions(boolean includeChildren) {

        List<String> perms = new ArrayList<>();

        registeredPermissions.keySet().stream().filter((key) -> (!perms.contains(key))).map((key) -> {
            perms.add(key);
            return key;
        }).filter((key) -> (includeChildren)).map((key) -> getAllChildren(key, new HashSet<>())).filter((children) -> (children != null)).forEach((children) -> {
            children.keySet().stream().filter((node) -> (!perms.contains(node))).forEach(perms::add);
        });
        return perms;
    }

    /**
     * Returns a map of ALL child permissions registered with bukkit null is
     * empty
     *
     * @param node
     * @param playerPermArray current list of perms to check against for
     * negations
     * @return Map of child permissions
     */
    public Map<String, Boolean> getAllChildren(String node, Set<String> playerPermArray) {

        LinkedList<String> stack = new LinkedList<>();
        Map<String, Boolean> alreadyVisited = new HashMap<>();
        stack.push(node);
        alreadyVisited.put(node, true);

        while (!stack.isEmpty()) {
            String now = stack.pop();

            Map<String, Boolean> children = getChildren(now);

            if ((children != null) && (!playerPermArray.contains("-" + now))) {
                children.keySet().stream().filter((childName) -> (!alreadyVisited.containsKey(childName))).map((childName) -> {
                    stack.push(childName);
                    return childName;
                }).forEach((childName) -> alreadyVisited.put(childName, children.get(childName)));
            }
        }
        alreadyVisited.remove(node);
        if (!alreadyVisited.isEmpty()) {
            return alreadyVisited;
        }

        return null;
    }

    /**
     * Returns a map of the child permissions (1 node deep) as registered with
     * Bukkit. null is empty
     *
     * @param node
     * @return Map of child permissions
     */
    public Map<String, Boolean> getChildren(String node) {

        Permission perm = registeredPermissions.get(node.toLowerCase());
        if (perm == null) {
            return null;
        }

        return perm.getChildren();

    }

    /**
     * List all effective permissions for this player.
     *
     * @param player
     * @return List<String> of permissions
     */
    public List<String> listPerms(Player player) {

        List<String> perms = new ArrayList<>();

        /*
         * // All permissions registered with Bukkit for this player
         * PermissionAttachment attachment = this.attachments.get(player);
         * 
         * // List perms for this player perms.add("Attachment Permissions:");
         * for(Map.Entry<String, Boolean> entry :
         * attachment.getPermissions().entrySet()){ perms.add(" " +
         * entry.getKey() + " = " + entry.getValue()); }
         */
        perms.add("Effective Permissions:");
        player.getEffectivePermissions().stream().filter(PermissionAttachmentInfo::getValue).forEach((info) -> perms.add(" " + info.getPermission() + " = " + info.getValue()));
        return perms;
    }

    /**
     * force Bukkit to update every OnlinePlayers permissions.
     */
    public void updateAllPlayers() {

        Bukkit.getServer().getOnlinePlayers().forEach(this::updatePermissions);
    }

    /**
     * force Bukkit to update this Players permissions.
     *
     * @param player
     */
    public void updatePlayer(Player player) {

        if (player != null) {
            this.updatePermissions(player, null);
        }
    }

    /**
     * Force remove any attachments
     *
     * @param playerName
     */
    private void removeAttachment(String playerName) {

        if (attachments.containsKey(playerName)) {
            attachments.get(playerName).remove();
            attachments.remove(playerName);
        }
    }

    /**
     * Remove all attachments in case of a restart or reload.
     */
    public void removeAllAttachments() {

        /*
         * Remove all attachments.
         */
        attachments.keySet().forEach((key) ->attachments.get(key).remove());
        attachments.clear();
    }

    /**
     * Player events tracked to cause Superperms updates
     *
     * @author ElgarL
     *
     */
    protected class PlayerEvents implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerJoin(PlayerJoinEvent event) {

            setPlayer_join(true);
            Player player = event.getPlayer();

            GroupManager.logger.log(Level.FINEST, "Player Join event: {0}", player.getName());

            /*
             * Tidy up any lose ends
             */
            removeAttachment(player.getName());

            // force GM to create the player if they are not already listed.
            plugin.getWorldsHolder().getWorldData(player.getWorld().getName()).getUser(player.getUniqueId().toString(), player.getName());

            setPlayer_join(false);
            updatePermissions(event.getPlayer());

            setPlayer_join(false);
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerChangeWorld(PlayerChangedWorldEvent event) { // has changed worlds

            updatePermissions(event.getPlayer(), event.getPlayer().getWorld().getName());
        }

        /*
         * Trigger at highest so we tidy up last.
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPlayerQuit(PlayerQuitEvent event) {

            if (!GroupManager.isLoaded()) {
                return;
            }

            Player player = event.getPlayer();
			String uuid = player.getUniqueId().toString();

			// Reset the User objects player reference.
			User user = plugin.getWorldsHolder().getWorldData(player.getWorld().getName()).getUser(uuid, player.getName());

			user.updatePlayer(null);

            /*
             * force remove any attachments as bukkit may not
             */
            removeAttachment(player.getName());
        }
    }

    protected class BukkitEvents implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onPluginEnable(PluginEnableEvent event) {

            if (!GroupManager.isLoaded()) {
                return;
            }

            collectPermissions();
            updateAllPlayers();
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onPluginDisable(PluginDisableEvent event) {

            collectPermissions();
            // updateAllPlayers();
        }
    }

}
