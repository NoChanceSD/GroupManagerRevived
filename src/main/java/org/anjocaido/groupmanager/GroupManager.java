/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.anjocaido.groupmanager;

import org.anjocaido.groupmanager.permissions.AnjoPermissionsHandler;
import org.anjocaido.groupmanager.permissions.BukkitPermissions;
import org.anjocaido.groupmanager.utils.GroupManagerPermissions;
import org.anjocaido.groupmanager.tasks.BukkitPermsUpdateTask;
import org.anjocaido.groupmanager.data.Variables;
import org.anjocaido.groupmanager.data.User;
import org.anjocaido.groupmanager.data.Group;
import org.anjocaido.groupmanager.dataholder.OverloadedWorldHolder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.anjocaido.groupmanager.dataholder.worlds.WorldsHolder;
import org.anjocaido.groupmanager.events.GMSystemEvent;
import org.anjocaido.groupmanager.events.GMWorldListener;
import org.anjocaido.groupmanager.events.GroupManagerEventHandler;
import org.anjocaido.groupmanager.utils.GMLoggerHandler;
import org.anjocaido.groupmanager.utils.PermissionCheckResult;
import org.anjocaido.groupmanager.utils.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 *
 * @author gabrielcouto, ElgarL
 */
public class GroupManager extends JavaPlugin {

    private File backupFolder;
    private Runnable commiter;
    private ScheduledThreadPoolExecutor scheduler;
    private Map<String, ArrayList<User>> overloadedUsers = new HashMap<>();
    private Map<String, String> selectedWorlds = new HashMap<>();
    private WorldsHolder worldsHolder;
    private boolean validateOnlinePlayer = true;

    private static boolean isLoaded = false;
    protected GMConfiguration config;

    protected static GlobalGroups globalGroups;

    private GMLoggerHandler ch;

    private static GroupManagerEventHandler GMEventHandler;
    public static BukkitPermissions BukkitPermissions;
    private static GMWorldListener WorldEvents;
    public static final Logger logger = Logger.getLogger(GroupManager.class.getName());

    // PERMISSIONS FOR COMMAND BEING LOADED
    private OverloadedWorldHolder dataHolder = null;
    private AnjoPermissionsHandler permissionHandler = null;

    private String lastError = "";

    @Override
    public void onDisable() {

        onDisable(false);
    }

    @Override
    public void onEnable() {
        /*
         * Initialize the event handler
         */
        setGMEventHandler(new GroupManagerEventHandler(this));
        onEnable(false);
    }

    public void onDisable(boolean restarting) {

        setLoaded(false);

        if (!restarting) {
            // Unregister this service if we are shutting down.
            this.getServer().getServicesManager().unregister(this.worldsHolder);
        }

        disableScheduler(); // Shutdown before we save, so it doesn't interfere.
        if (worldsHolder != null) {
            try {
                worldsHolder.saveChanges(false);
            } catch (IllegalStateException ex) {
                GroupManager.logger.log(Level.WARNING, ex.getMessage());
            }
        }

        // Remove all attachments before clearing
        if (BukkitPermissions != null) {
            BukkitPermissions.removeAllAttachments();
        }

        if (!restarting) {

            if (WorldEvents != null) {
                WorldEvents = null;
            }

            BukkitPermissions = null;

        }

        // EXAMPLE: Custom code, here we just output some info so we can check that all is well
        PluginDescriptionFile pdfFile = this.getDescription();
        System.out.println(pdfFile.getName() + " version " + pdfFile.getVersion() + " is disabled!");

        if (!restarting) {
            GroupManager.logger.removeHandler(ch);
        }
    }

    public void onEnable(boolean restarting) {

        try {
            /*
             * reset local variables.
             */
            overloadedUsers = new HashMap<>();
            selectedWorlds = new HashMap<>();
            lastError = "";

            /*
             * Setup our logger if we are not restarting.
             */
            if (!restarting) {
                GroupManager.logger.setUseParentHandlers(false);
                ch = new GMLoggerHandler();
                GroupManager.logger.addHandler(ch);
            }
            GroupManager.logger.setLevel(Level.ALL);

            // Create the backup folder, if it doesn't exist.
            prepareFileFields();
            // Load the config.yml
            prepareConfig();
            // Load the global groups
            globalGroups = new GlobalGroups(this);

            /*
             * Configure the worlds holder.
             */
            if (!restarting) {
                worldsHolder = new WorldsHolder(this);
            } else {
                worldsHolder.resetWorldsHolder();
            }

            /*
             * This should NEVER happen. No idea why it's still here.
             */
            PluginDescriptionFile pdfFile = this.getDescription();
            if (worldsHolder == null) {
                GroupManager.logger.severe("Can't enable " + pdfFile.getName() + " version " + pdfFile.getVersion() + ", bad loading!");
                this.getServer().getPluginManager().disablePlugin(this);
                throw new IllegalStateException("An error ocurred while loading GroupManager");
            }

            /*
             *  Prevent our registered events from triggering
             *  updates as we are not fully loaded.
             */
            setLoaded(false);

            /*
             *  Initialize the world listener and bukkit permissions
             *  to handle events if this is a fresh start
             *  
             *  else
             *  
             *  Reset bukkit perms.
             */
            if (!restarting) {
                WorldEvents = new GMWorldListener(this);
                BukkitPermissions = new BukkitPermissions(this);
            } else {
                BukkitPermissions.reset();
            }

            /*
             * Start the scheduler for data saving.
             */
            enableScheduler();

            /*
             * Schedule a Bukkit Permissions update for 1 tick later.
             * All plugins will be loaded by then
             */
            if (getServer().getScheduler().scheduleSyncDelayedTask(this, new BukkitPermsUpdateTask(), 1) == -1) {
                GroupManager.logger.severe("Could not schedule superperms Update.");
                /*
                 * Flag that we are now loaded and should start processing events.
                 */
                setLoaded(true);
            }

            System.out.println(pdfFile.getName() + " version " + pdfFile.getVersion() + " is enabled!");

            // Register as a service
            if (!restarting) {
                this.getServer().getServicesManager().register(WorldsHolder.class, this.worldsHolder, this, ServicePriority.Lowest);
            }

        } catch (Exception ex) {

            /*
             * Store the error and write to the log.
             */
            saveErrorLog(ex);

            /*
             * Throw an error so Bukkit knows about it.
             */
            throw new IllegalArgumentException(ex.getMessage(), ex);

        }
    }

    /**
     * Write an error.log
     *
     * @param ex
     */
    private void saveErrorLog(Exception ex) {

        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        lastError = ex.getMessage();

        GroupManager.logger.severe("===================================================");
        GroupManager.logger.severe("= ERROR REPORT START - " + this.getDescription().getVersion() + " =");
        GroupManager.logger.severe("===================================================");
        GroupManager.logger.severe("=== PLEASE COPY AND PASTE THE ERROR.LOG FROM THE ==");
        GroupManager.logger.severe("= GROUPMANAGER FOLDER TO AN ESSENTIALS  DEVELOPER =");
        GroupManager.logger.severe("===================================================");
        GroupManager.logger.severe(lastError);
        GroupManager.logger.severe("===================================================");
        GroupManager.logger.severe("= ERROR REPORT ENDED =");
        GroupManager.logger.severe("===================================================");

        // Append this error to the error log.
        try {
            String error = "=============================== GM ERROR LOG ===============================\n";
            error += "= ERROR REPORT START - " + this.getDescription().getVersion() + " =\n\n";

            error += Tasks.getStackTraceAsString(ex);
            error += "\n============================================================================\n";

            Tasks.appendStringToFile(error, (getDataFolder() + System.getProperty("file.separator") + "ERROR.LOG"));
        } catch (IOException e) {
            // Failed to write file.
            e.printStackTrace();
        }

    }

    /**
     * @return the validateOnlinePlayer
     */
    public boolean isValidateOnlinePlayer() {

        return validateOnlinePlayer;
    }

    /**
     * @param validateOnlinePlayer the validateOnlinePlayer to set
     */
    public void setValidateOnlinePlayer(boolean validateOnlinePlayer) {

        this.validateOnlinePlayer = validateOnlinePlayer;
    }

    public static boolean isLoaded() {

        return isLoaded;
    }

    public static void setLoaded(boolean isLoaded) {

        GroupManager.isLoaded = isLoaded;
    }

    public InputStream getResourceAsStream(String fileName) {

        return this.getClassLoader().getResourceAsStream(fileName);
    }

    private void prepareFileFields() {

        backupFolder = new File(this.getDataFolder(), "backup");
        if (!backupFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            getBackupFolder().mkdirs();
        }
    }

    private void prepareConfig() {

        config = new GMConfiguration(this);
    }

    public void enableScheduler() {

        if (worldsHolder != null) {
            disableScheduler();
            commiter = () -> {
                try {
                    if (worldsHolder.saveChanges(false)) {
                        GroupManager.logger.log(Level.INFO, " Data files refreshed.");
                    }
                } catch (IllegalStateException ex) {
                    GroupManager.logger.log(Level.WARNING, ex.getMessage());
                }
            };
            scheduler = new ScheduledThreadPoolExecutor(1);
            long minutes = (long) getGMConfig().getSaveInterval();
            if (minutes > 0) {
                scheduler.scheduleAtFixedRate(commiter, minutes, minutes, TimeUnit.MINUTES);
                GroupManager.logger.info("Scheduled Data Saving is set for every " + minutes + " minutes!");
            } else {
                GroupManager.logger.info("Scheduled Data Saving is Disabled!");
            }

            GroupManager.logger.info("Backups will be retained for " + getGMConfig().getBackupDuration() + " hours!");
        }
    }

    public void disableScheduler() {

        if (scheduler != null) {
            try {
                scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
                scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                scheduler.shutdown();
            } catch (Exception ignored) {
            }
            scheduler = null;
            GroupManager.logger.info("Scheduled Data Saving is disabled!");
        }
    }

    public WorldsHolder getWorldsHolder() {

        return worldsHolder;
    }

    /**
     * Called when a command registered by this plugin is received.
     *
     * @param sender
     * @param cmd
     * @param commandLabel
     * @param args
     * @return
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {

        boolean playerCanDo = false;
        boolean isConsole = false;
        Player senderPlayer = null, targetPlayer = null;
        Group senderGroup = null;
        User senderUser = null;
        boolean isOpOverride = config.isOpOverride();
        boolean isAllowCommandBlocks = config.isAllowCommandBlocks();

        // PREVENT GM COMMANDS BEING USED ON COMMANDBLOCKS
        if (sender instanceof BlockCommandSender && !isAllowCommandBlocks) {
            Block block = ((BlockCommandSender) sender).getBlock();
            GroupManager.logger.warning(ChatColor.RED + "GM Commands can not be called from CommandBlocks");
            GroupManager.logger.warning(ChatColor.RED + "Location: " + ChatColor.GREEN + block.getWorld().getName() + ", " + block.getX() + ", " + block.getY() + ", " + block.getZ());
            return true;
        }

        // DETERMINING PLAYER INFORMATION
        if (sender instanceof Player) {
            senderPlayer = (Player) sender;

            if (!lastError.isEmpty() && !commandLabel.equalsIgnoreCase("manload")) {
                sender.sendMessage(ChatColor.RED + "All commands are locked due to an error. " + ChatColor.BOLD + "" + ChatColor.UNDERLINE + "Check plugins/groupmanager/error.log or console" + ChatColor.RESET + "" + ChatColor.RED + " and then try a '/manload'.");
                return true;
            }

            senderUser = worldsHolder.getWorldData(senderPlayer).getUser(senderPlayer.getName());
            senderGroup = senderUser.getGroup();
            isOpOverride = (isOpOverride && (senderPlayer.isOp() || worldsHolder.getWorldPermissions(senderPlayer).has(senderPlayer, "groupmanager.op")));

            if (isOpOverride || worldsHolder.getWorldPermissions(senderPlayer).has(senderPlayer, "groupmanager." + cmd.getName())) {
                playerCanDo = true;
            }
        } else {

            if (!lastError.isEmpty() && !commandLabel.equalsIgnoreCase("manload")) {
                sender.sendMessage(ChatColor.RED + "All commands are locked due to an error. " + ChatColor.BOLD + "" + ChatColor.UNDERLINE + "Check plugins/groupmanager/error.log or console" + ChatColor.RESET + "" + ChatColor.RED + " and then try a '/manload'.");
                return true;
            }

            isConsole = true;
        }

        // PERMISSIONS FOR COMMAND BEING LOADED
        dataHolder = null;
        permissionHandler = null;

        if (senderPlayer != null) {
            dataHolder = worldsHolder.getWorldData(senderPlayer);
        }

        String selectedWorld = selectedWorlds.get(sender.getName());
        if (selectedWorld != null) {
            dataHolder = worldsHolder.getWorldData(selectedWorld);
        }

        if (dataHolder != null) {
            permissionHandler = dataHolder.getPermissionsHandler();
        }

		// VARIABLES USED IN COMMANDS
        int count;
        PermissionCheckResult permissionResult;
        ArrayList<User> removeList;
        String auxString = null;
        List<String> match = null;
        User auxUser;
        Group auxGroup;
        Group auxGroup2;

        // TODO: Replace this mess of a command system...

        GroupManagerPermissions execCmd;
        try {
            execCmd = GroupManagerPermissions.valueOf(cmd.getName());
        } catch (Exception e) {
            // this error happened once with someone. now im prepared... i think
            GroupManager.logger.severe("===================================================");
            GroupManager.logger.severe("= ERROR REPORT START =");
            GroupManager.logger.severe("===================================================");
            GroupManager.logger.severe("= COPY AND PASTE THIS TO A GROUPMANAGER DEVELOPER =");
            GroupManager.logger.severe("===================================================");
            GroupManager.logger.severe(this.getDescription().getName());
            GroupManager.logger.severe(this.getDescription().getVersion());
            GroupManager.logger.severe("An error occured while trying to execute command:");
            GroupManager.logger.severe(cmd.getName());
            GroupManager.logger.severe("With " + args.length + " arguments:");
            for (String ar : args) {
                GroupManager.logger.severe(ar);
            }
            GroupManager.logger.severe("The field '" + cmd.getName() + "' was not found in enum.");
            GroupManager.logger.severe("And could not be parsed.");
            GroupManager.logger.severe("FIELDS FOUND IN ENUM:");
            for (GroupManagerPermissions val : GroupManagerPermissions.values()) {
                GroupManager.logger.severe(val.name());
            }
            GroupManager.logger.severe("===================================================");
            GroupManager.logger.severe("= ERROR REPORT ENDED =");
            GroupManager.logger.severe("===================================================");
            sender.sendMessage("An error occurred. Ask the admin to take a look at the console.");

            // Exception occurred, We're just going to throw an NPE when we hit the switch.
            return true;
        }

        if (isConsole || playerCanDo) {
            switch (execCmd) {
                case manuadd:

                    // Validating arguments
                    if ((args.length != 2) && (args.length != 3)) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manuadd <player> <group> | optional [world])");
                        return true;
                    }

                    // Select the relevant world (if specified)
                    if (args.length == 3) {
                        dataHolder = worldsHolder.getWorldData(args[2]);
                        permissionHandler = dataHolder.getPermissionsHandler();
                    }

                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }

                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    auxGroup = dataHolder.getGroup(args[1]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[1] + "' Group doesnt exist!");
                        return false;
                    }
                    if (auxGroup.isGlobal()) {
                        sender.sendMessage(ChatColor.RED + "Players may not be members of GlobalGroups directly.");
                        return false;
                    }

                    // Validating permissions
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "Can't modify a player with the same permissions as you, or higher.");
                        return true;
                    }
                    if (!isConsole && !isOpOverride && (permissionHandler.hasGroupInInheritance(auxGroup, senderGroup != null ? senderGroup.getName() : null))) {
                        sender.sendMessage(ChatColor.RED + "The destination group can't be the same as yours, or higher.");
                        return true;
                    }
                    if (!isConsole && !isOpOverride && (!permissionHandler.inGroup(senderUser.getLastName(), auxUser.getGroupName()) || !permissionHandler.inGroup(senderUser.getLastName(), auxGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player involving a group that you don't inherit.");
                        return true;
                    }

                    // Seems OK
                    auxUser.setGroup(auxGroup);
                    if (!sender.hasPermission("groupmanager.notify.other") || (isConsole)) {
                        sender.sendMessage(ChatColor.YELLOW + "You changed player '" + auxUser.getLastName() + "' group to '" + auxGroup.getName() + "' in world '" + dataHolder.getName() + "'.");
                    }

                    return true;

                case manudel:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manudel <player>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    // Validating permission
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player with same permissions as you, or higher.");
                        return true;
                    }
                    // Seems OK
                    dataHolder.removeUser(auxUser.getLastName());
                    sender.sendMessage(ChatColor.YELLOW + "You changed player '" + auxUser.getLastName() + "' to default settings.");

                    // If the player is online, this will create new data for the user.
                    targetPlayer = this.getServer().getPlayer(auxUser.getLastName());
                    if (targetPlayer != null) {
                        BukkitPermissions.updatePermissions(targetPlayer);
                    }

                    return true;

                case manuaddsub:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            sender.sendMessage(ChatColor.RED + "Couldn't retrieve your world. World selection is needed.");
                            sender.sendMessage(ChatColor.RED + "Use /manselect <world>");
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manuaddsub <player> <group>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    auxGroup = dataHolder.getGroup(args[1]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[1] + "' Group doesnt exist!");
                        return true;
                    }
                    // Validating permission
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player with same permissions as you, or higher.");
                        return true;
                    }
                    if (!isConsole && !isOpOverride && (permissionHandler.hasGroupInInheritance(auxGroup, senderGroup != null ? senderGroup.getName() : null))) {
                        sender.sendMessage(ChatColor.RED + "The sub-group can't be the same as yours, or higher.");
                        return true;
                    }
                    if (!isConsole && !isOpOverride && (!permissionHandler.inGroup(senderUser.getLastName(), auxUser.getGroupName()) || !permissionHandler.inGroup(senderUser.getLastName(), auxGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player involving a group that you don't inherit.");
                        return true;
                    }
                    // Seems OK
                    if (auxUser.addSubGroup(auxGroup)) {
                        sender.sendMessage(ChatColor.YELLOW + "You added subgroup '" + auxGroup.getName() + "' to player '" + auxUser.getLastName() + "'.");
                    } else {
                        sender.sendMessage(ChatColor.RED + "The subgroup '" + auxGroup.getName() + "' is already available to '" + auxUser.getLastName() + "'.");
                    }

                    return true;

                case manudelsub:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manudelsub <user> <group>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    auxGroup = dataHolder.getGroup(args[1]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[1] + "' Group doesnt exist!");
                        return true;
                    }

                    // Validating permission
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player with same permissions as you, or higher.");
                        return true;
                    }
                    // Seems OK
                    auxUser.removeSubGroup(auxGroup);
                    sender.sendMessage(ChatColor.YELLOW + "You removed subgroup '" + auxGroup.getName() + "' from player '" + auxUser.getLastName() + "' list.");

				// targetPlayer = this.getServer().getPlayer(auxUser.getName());
                    // if (targetPlayer != null)
                    // BukkitPermissions.updatePermissions(targetPlayer);
                    return true;

                case mangadd:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangadd <group>)");
                        return true;
                    }
                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup != null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group already exists!");
                        return true;
                    }
                    // Seems OK
                    auxGroup = dataHolder.createGroup(args[0]);
                    sender.sendMessage(ChatColor.YELLOW + "You created a group named: " + auxGroup.getName());

                    return true;

                case mangdel:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangdel <group>)");
                        return false;
                    }
                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
                    // Seems OK
                    dataHolder.removeGroup(auxGroup.getName());
                    sender.sendMessage(ChatColor.YELLOW + "You deleted a group named " + auxGroup.getName() + ", it's users are default group now.");

                    BukkitPermissions.updateAllPlayers();

                    return true;

                case manuaddp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manuaddp <player> <permission> [permission2] [permission3]...)");
                        return true;
                    }

                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }

                    // Validating your permissions
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "Can't modify player with same group than you, or higher.");
                        return true;
                    }

                    for (int i = 1; i < args.length; i++) {
                        auxString = args[i].replace("'", "");

                        permissionResult = permissionHandler.checkFullUserPermission(senderUser, auxString);
                        if (!isConsole && !isOpOverride && (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND) || permissionResult.resultType.equals(PermissionCheckResult.Type.NEGATION))) {
                            sender.sendMessage(ChatColor.RED + "You can't add a permission you don't have: '" + auxString + "'");
                            continue;
                        }
                        // Validating permissions of user
                        permissionResult = permissionHandler.checkUserOnlyPermission(auxUser, auxString);
                        if (checkPermissionExists(sender, auxString, permissionResult, "user")) {
                            continue;
                        }
                        // Seems Ok
                        auxUser.addPermission(auxString);
                        sender.sendMessage(ChatColor.YELLOW + "You added '" + auxString + "' to player '" + auxUser.getLastName() + "' permissions.");
                    }

                    targetPlayer = this.getServer().getPlayer(auxUser.getLastName());
                    if (targetPlayer != null) {
                        BukkitPermissions.updatePermissions(targetPlayer);
                    }

                    return true;

                case manudelp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manudelp <player> <permission> [permission2] [permission3]...)");
                        return true;
                    }

                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }

                    for (int i = 1; i < args.length; i++) {
                        auxString = args[i].replace("'", "");

                        if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                            sender.sendMessage(ChatColor.RED + "You can't modify a player with same group as you, or higher.");
                            continue;
                        }
                        // Validating your permissions
                        permissionResult = permissionHandler.checkFullUserPermission(senderUser, auxString);
                        if (!isConsole && !isOpOverride && (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND) || permissionResult.resultType.equals(PermissionCheckResult.Type.NEGATION))) {
                            sender.sendMessage(ChatColor.RED + "You can't remove a permission you don't have: '" + auxString + "'");
                            continue;
                        }
                        // Validating permissions of user
                        permissionResult = permissionHandler.checkUserOnlyPermission(auxUser, auxString);
                        if (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND)) {
                            sender.sendMessage(ChatColor.RED + "The user doesn't have direct access to that permission: '" + auxString + "'");
                            continue;
                        }
                        if (!auxUser.hasSamePermissionNode(auxString)) {
                            sender.sendMessage(ChatColor.RED + "This permission node doesn't match any node.");
                            sender.sendMessage(ChatColor.RED + "But might match node: " + permissionResult.accessLevel);
                            continue;
                        }
                        auxUser.removePermission(auxString);
                        sender.sendMessage(ChatColor.YELLOW + "You removed '" + auxString + "' from player '" + auxUser.getLastName() + "' permissions.");
                    }
                    // Seems OK

                    targetPlayer = this.getServer().getPlayer(auxUser.getLastName());
                    if (targetPlayer != null) {
                        BukkitPermissions.updatePermissions(targetPlayer);
                    }

                    return true;

                case manuclearp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manuclearp <player>)");
                        return true;
                    }

                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    // Validating your permissions
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player with same group as you, or higher.");
                        return true;
                    }
                    for (String perm : auxUser.getPermissionList()) {
                        permissionResult = permissionHandler.checkFullUserPermission(senderUser, perm);
                        if (!isConsole && !isOpOverride && (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND) || permissionResult.resultType.equals(PermissionCheckResult.Type.NEGATION))) {
                            sender.sendMessage(ChatColor.RED + "You can't remove a permission you don't have: '" + perm + "'.");
                        } else {
                            auxUser.removePermission(perm);
                        }
                    }
                    sender.sendMessage(ChatColor.YELLOW + "You removed all permissions from player '" + auxUser.getLastName() + "'.");

                    targetPlayer = this.getServer().getPlayer(auxUser.getLastName());
                    if (targetPlayer != null) {
                        BukkitPermissions.updatePermissions(targetPlayer);
                    }

                    return true;

                case manulistp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if ((args.length == 0) || (args.length > 2)) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manulistp <player> (+))");
                        return true;
                    }

                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
				// Validating permission
                    // Seems OK
                    auxString = "";
                    for (String perm : auxUser.getPermissionList()) {
                        auxString += perm + ", ";
                    }
                    if (auxString.lastIndexOf(",") > 0) {
                        auxString = auxString.substring(0, auxString.lastIndexOf(","));
                        sender.sendMessage(ChatColor.YELLOW + "The player '" + auxUser.getLastName() + "' has following permissions: " + ChatColor.WHITE + auxString);
                        sender.sendMessage(ChatColor.YELLOW + "And all permissions from group: " + auxUser.getGroupName());
                        auxString = "";
                        for (String subGroup : auxUser.subGroupListStringCopy()) {
                            auxString += subGroup + ", ";
                        }
                        if (auxString.lastIndexOf(",") > 0) {
                            auxString = auxString.substring(0, auxString.lastIndexOf(","));
                            sender.sendMessage(ChatColor.YELLOW + "And all permissions from subgroups: " + auxString);
                        }
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "The player '" + auxUser.getLastName() + "' has no specific permissions.");
                        sender.sendMessage(ChatColor.YELLOW + "Only all permissions from group: " + auxUser.getGroupName());
                        auxString = "";
                        for (String subGroup : auxUser.subGroupListStringCopy()) {
                            auxString += subGroup + ", ";
                        }
                        if (auxString.lastIndexOf(",") > 0) {
                            auxString = auxString.substring(0, auxString.lastIndexOf(","));
                            sender.sendMessage(ChatColor.YELLOW + "And all permissions from subgroups: " + auxString);
                        }
                    }

                    // bukkit perms
                    if ((args.length == 2) && (args[1].equalsIgnoreCase("+"))) {
                        targetPlayer = this.getServer().getPlayer(auxUser.getLastName());
                        if (targetPlayer != null) {
                            sender.sendMessage(ChatColor.YELLOW + "Superperms reports: ");
                            for (String line : BukkitPermissions.listPerms(targetPlayer)) {
                                sender.sendMessage(ChatColor.YELLOW + line);
                            }

                        }
                    }

                    return true;

                case manucheckp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manucheckp <player> <permission>)");
                        return true;
                    }

                    auxString = args[1].replace("'", "");

                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    targetPlayer = this.getServer().getPlayer(auxUser.getLastName());
                    // Validating permission
                    permissionResult = permissionHandler.checkFullGMPermission(auxUser, auxString, false);

                    if (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND)) {
                        // No permissions found in GM so fall through and check Bukkit.
                        sender.sendMessage(ChatColor.YELLOW + "The player doesn't have access to that permission");

                    } else {
                        // This permission was found in groupmanager.
                        if (permissionResult.owner instanceof User) {
                            if (permissionResult.resultType.equals(PermissionCheckResult.Type.NEGATION)) {
                                sender.sendMessage(ChatColor.YELLOW + "The user has directly a negation node for that permission.");
                            } else if (permissionResult.resultType.equals(PermissionCheckResult.Type.EXCEPTION)) {
                                sender.sendMessage(ChatColor.YELLOW + "The user has directly an Exception node for that permission.");
                            } else {
                                sender.sendMessage(ChatColor.YELLOW + "The user has directly this permission.");
                            }
                            sender.sendMessage(ChatColor.YELLOW + "Permission Node: " + permissionResult.accessLevel);
                        } else if (permissionResult.owner instanceof Group) {
                            if (permissionResult.resultType.equals(PermissionCheckResult.Type.NEGATION)) {
                                sender.sendMessage(ChatColor.YELLOW + "The user inherits a negation permission from group: " + permissionResult.owner.getLastName());
                            } else if (permissionResult.resultType.equals(PermissionCheckResult.Type.EXCEPTION)) {
                                sender.sendMessage(ChatColor.YELLOW + "The user inherits an Exception permission from group: " + permissionResult.owner.getLastName());
                            } else {
                                sender.sendMessage(ChatColor.YELLOW + "The user inherits the permission from group: " + permissionResult.owner.getLastName());
                            }
                            sender.sendMessage(ChatColor.YELLOW + "Permission Node: " + permissionResult.accessLevel);
                        }
                    }

                    // superperms
                    if (targetPlayer != null) {
                        sender.sendMessage(ChatColor.YELLOW + "SuperPerms reports Node: " + targetPlayer.hasPermission(args[1]) + ((!targetPlayer.hasPermission(args[1]) && targetPlayer.isPermissionSet(args[1])) ? " (Negated)" : ""));
                    }

                    return true;

                case mangaddp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangaddp <group> <permission> [permission2] [permission3]...)");
                        return true;
                    }

                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return false;
                    }

                    for (int i = 1; i < args.length; i++) {
                        auxString = args[i].replace("'", "");

                        // Validating your permissions
                        permissionResult = permissionHandler.checkFullUserPermission(senderUser, auxString);
                        if (!isConsole && !isOpOverride && (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND) || permissionResult.resultType.equals(PermissionCheckResult.Type.NEGATION))) {
                            sender.sendMessage(ChatColor.RED + "You can't add a permission you don't have: '" + auxString + "'");
                            continue;
                        }
                        // Validating permissions of user
                        permissionResult = permissionHandler.checkGroupOnlyPermission(auxGroup, auxString);
                        if (checkPermissionExists(sender, auxString, permissionResult, "group")) {
                            continue;
                        }
                        // Seems OK
                        auxGroup.addPermission(auxString);
                        sender.sendMessage(ChatColor.YELLOW + "You added '" + auxString + "' to group '" + auxGroup.getName() + "' permissions.");
                    }

                    BukkitPermissions.updateAllPlayers();

                    return true;

                case mangdelp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangdelp <group> <permission> [permission2] [permission3]...)");
                        return true;
                    }

                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
                    for (int i = 1; i < args.length; i++) {
                        auxString = args[i].replace("'", "");

                        // Validating your permissions
                        permissionResult = permissionHandler.checkFullUserPermission(senderUser, auxString);
                        if (!isConsole && !isOpOverride && (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND) || permissionResult.resultType.equals(PermissionCheckResult.Type.NEGATION))) {
                            sender.sendMessage(ChatColor.RED + "Can't remove a permission you don't have: '" + auxString + "'");
                            continue;
                        }
                        // Validating permissions of user
                        permissionResult = permissionHandler.checkGroupOnlyPermission(auxGroup, auxString);
                        if (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND)) {
                            sender.sendMessage(ChatColor.YELLOW + "The group doesn't have direct access to that permission: '" + auxString + "'");
                            continue;
                        }
                        if (!auxGroup.hasSamePermissionNode(auxString)) {
                            sender.sendMessage(ChatColor.RED + "This permission node doesn't match any node.");
                            sender.sendMessage(ChatColor.RED + "But might match node: " + permissionResult.accessLevel);
                            continue;
                        }
                        // Seems OK
                        auxGroup.removePermission(auxString);
                        sender.sendMessage(ChatColor.YELLOW + "You removed '" + auxString + "' from group '" + auxGroup.getName() + "' permissions.");
                    }

                    BukkitPermissions.updateAllPlayers();

                    return true;

                case mangclearp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangclearp <group>)");
                        return true;
                    }

                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }

                    for (String perm : auxGroup.getPermissionList()) {
                        permissionResult = permissionHandler.checkFullUserPermission(senderUser, perm);
                        if (!isConsole && !isOpOverride && (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND) || permissionResult.resultType.equals(PermissionCheckResult.Type.NEGATION))) {
                            sender.sendMessage(ChatColor.RED + "Can't remove a permission you don't have: '" + perm + "'.");
                        } else {
                            auxGroup.removePermission(perm);
                        }
                    }
                    sender.sendMessage(ChatColor.YELLOW + "You removed all permissions from group '" + auxGroup.getName() + "'.");

                    BukkitPermissions.updateAllPlayers();

                    return true;

                case manglistp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manglistp <group>)");
                        return true;
                    }
                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
				// Validating permission

                    // Seems OK
                    auxString = "";
                    for (String perm : auxGroup.getPermissionList()) {
                        auxString += perm + ", ";
                    }
                    if (auxString.lastIndexOf(",") > 0) {
                        auxString = auxString.substring(0, auxString.lastIndexOf(","));
                        sender.sendMessage(ChatColor.YELLOW + "The group '" + auxGroup.getName() + "' has following permissions: " + ChatColor.WHITE + auxString);
                        auxString = "";
                        for (String grp : auxGroup.getInherits()) {
                            auxString += grp + ", ";
                        }
                        if (auxString.lastIndexOf(",") > 0) {
                            auxString = auxString.substring(0, auxString.lastIndexOf(","));
                            sender.sendMessage(ChatColor.YELLOW + "And all permissions from groups: " + auxString);
                        }

                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "The group '" + auxGroup.getName() + "' has no specific permissions.");
                        auxString = "";
                        for (String grp : auxGroup.getInherits()) {
                            auxString += grp + ", ";
                        }
                        if (auxString.lastIndexOf(",") > 0) {
                            auxString = auxString.substring(0, auxString.lastIndexOf(","));
                            sender.sendMessage(ChatColor.YELLOW + "Only all permissions from groups: " + auxString);
                        }

                    }
                    return true;

                case mangcheckp:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangcheckp <group> <permission>)");
                        return true;
                    }

                    auxString = args[1];
                    if (auxString.startsWith("'") && auxString.endsWith("'")) {
                        auxString = auxString.substring(1, auxString.length() - 1);
                    }

                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
                    // Validating permission
                    permissionResult = permissionHandler.checkGroupPermissionWithInheritance(auxGroup, auxString);
                    if (permissionResult.resultType.equals(PermissionCheckResult.Type.NOTFOUND)) {
                        sender.sendMessage(ChatColor.YELLOW + "The group doesn't have access to that permission");
                        return true;
                    }
				// Seems OK
                    // auxString = permissionHandler.checkUserOnlyPermission(auxUser, args[1]);
                    if (permissionResult.owner instanceof Group) {
                        if (permissionResult.resultType.equals(PermissionCheckResult.Type.NEGATION)) {
                            sender.sendMessage(ChatColor.YELLOW + "The group inherits the negation permission from group: " + permissionResult.owner.getLastName());
                        } else if (permissionResult.resultType.equals(PermissionCheckResult.Type.EXCEPTION)) {
                            sender.sendMessage(ChatColor.YELLOW + "The group inherits an Exception permission from group: " + permissionResult.owner.getLastName());
                        } else {
                            sender.sendMessage(ChatColor.YELLOW + "The group inherits the permission from group: " + permissionResult.owner.getLastName());
                        }
                        sender.sendMessage(ChatColor.YELLOW + "Permission Node: " + permissionResult.accessLevel);

                    }
                    return true;

                case mangaddi:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangaddi <group1> <group2>)");
                        return true;
                    }
                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
                    auxGroup2 = dataHolder.getGroup(args[1]);
                    if (auxGroup2 == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[1] + "' Group doesnt exist!");
                        return true;
                    }
                    if (auxGroup.isGlobal()) {
                        sender.sendMessage(ChatColor.RED + "GlobalGroups do NOT support inheritance.");
                        return true;
                    }

                    // Validating permission
                    if (permissionHandler.hasGroupInInheritance(auxGroup, auxGroup2.getName())) {
                        sender.sendMessage(ChatColor.RED + "Group " + auxGroup.getName() + " already inherits " + auxGroup2.getName() + " (might not be directly)");
                        return true;
                    }
                    // Seems OK
                    auxGroup.addInherits(auxGroup2);
                    sender.sendMessage(ChatColor.YELLOW + "Group " + auxGroup2.getName() + " is now in " + auxGroup.getName() + " inheritance list.");

                    BukkitPermissions.updateAllPlayers();

                    return true;

                case mangdeli:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangdeli <group1> <group2>)");
                        return true;
                    }
                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
                    auxGroup2 = dataHolder.getGroup(args[1]);
                    if (auxGroup2 == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[1] + "' Group doesnt exist!");
                        return true;
                    }
                    if (auxGroup.isGlobal()) {
                        sender.sendMessage(ChatColor.RED + "GlobalGroups do NOT support inheritance.");
                        return true;
                    }

                    // Validating permission
                    if (!permissionHandler.hasGroupInInheritance(auxGroup, auxGroup2.getName())) {
                    	sender.sendMessage(ChatColor.RED + "Group " + auxGroup.getName() + " does not inherit " + auxGroup2.getName() + ".");
                        return true;
                    }
                    if (!auxGroup.getInherits().contains(auxGroup2.getName())) {
                    	sender.sendMessage(ChatColor.RED + "Group " + auxGroup.getName() + " does not inherit " + auxGroup2.getName() + " directly.");
                        return true;
                    }
                    // Seems OK
                    auxGroup.removeInherits(auxGroup2.getName());
                    sender.sendMessage(ChatColor.YELLOW + "Group " + auxGroup2.getName() + " was removed from " + auxGroup.getName() + " inheritance list.");

                    BukkitPermissions.updateAllPlayers();

                    return true;

                case manuaddv:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length < 3) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manuaddv <user> <variable> <value>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
				// Validating permission
                    // Seems OK
                    auxString = "";
                    for (int i = 2; i < args.length; i++) {
                        auxString += args[i];
                        if ((i + 1) < args.length) {
                            auxString += " ";
                        }
                    }
                    auxString = auxString.replace("'", "");
                    auxUser.getVariables().addVar(args[1], Variables.parseVariableValue(auxString));
                    sender.sendMessage(ChatColor.YELLOW + "Variable " + ChatColor.GOLD + args[1] + ChatColor.YELLOW + ":'" + ChatColor.GREEN + auxString + ChatColor.YELLOW + "' added to the user " + auxUser.getLastName());

                    return true;

                case manudelv:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manudelv <user> <variable>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }

                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    // Validating permission
                    if (!auxUser.getVariables().hasVar(args[1])) {
                        sender.sendMessage(ChatColor.RED + "The user doesn't have directly that variable!");
                        return true;
                    }
                    // Seems OK
                    auxUser.getVariables().removeVar(args[1]);
                    sender.sendMessage(ChatColor.YELLOW + "Variable " + ChatColor.GOLD + args[1] + ChatColor.YELLOW + " removed from the user " + ChatColor.GREEN + auxUser.getLastName());

                    return true;

                case manulistv:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manulistv <user>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }
                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
				// Validating permission
                    // Seems OK
                    auxString = "";
                    for (String varKey : auxUser.getVariables().getVarKeyList()) {
                        Object o = auxUser.getVariables().getVarObject(varKey);
                        auxString += ChatColor.GOLD + varKey + ChatColor.WHITE + ":'" + ChatColor.GREEN + o.toString() + ChatColor.WHITE + "', ";
                    }
                    if (auxString.lastIndexOf(",") > 0) {
                        auxString = auxString.substring(0, auxString.lastIndexOf(","));
                    }
                    sender.sendMessage(ChatColor.YELLOW + "Variables of user " + auxUser.getLastName() + ": ");
                    sender.sendMessage(auxString + ".");
                    sender.sendMessage(ChatColor.YELLOW + "Plus all variables from group: " + auxUser.getGroupName());

                    return true;

                case manucheckv:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manucheckv <user> <variable>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }
                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    // Validating permission
                    auxGroup = auxUser.getGroup();
                    auxGroup2 = permissionHandler.nextGroupWithVariable(auxGroup, args[1]);

                    if (!auxUser.getVariables().hasVar(args[1])) {
                        // Check sub groups
                        if (!auxUser.isSubGroupsEmpty() && auxGroup2 == null) {
                            for (Group subGroup : auxUser.subGroupListCopy()) {
                                auxGroup2 = permissionHandler.nextGroupWithVariable(subGroup, args[1]);
                            }
                        }

                        if (auxGroup2 == null) {
                            sender.sendMessage(ChatColor.YELLOW + "The user doesn't have access to that variable!");
                            return true;
                        }
                    }

                    // Seems OK
                    sender.sendMessage(ChatColor.YELLOW + "The value of variable '" + ChatColor.GOLD + args[1] + ChatColor.YELLOW + "' is: '" + ChatColor.GREEN + auxGroup2.getVariables().getVarObject(args[1]).toString() + ChatColor.WHITE + "'");
                    if (!auxGroup.equals(auxGroup2)) {
                        sender.sendMessage(ChatColor.YELLOW + "And the value was inherited from group: " + ChatColor.GREEN + auxGroup2.getName());
                    }

                    return true;

                case mangaddv:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length < 3) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangaddv <group> <variable> <value>)");
                        return true;
                    }
                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
                    if (auxGroup.isGlobal()) {
                        sender.sendMessage(ChatColor.RED + "GlobalGroups do NOT support Info Nodes.");
                        return true;
                    }
				// Validating permission
                    // Seems OK
                    auxString = "";
                    for (int i = 2; i < args.length; i++) {
                        auxString += args[i];
                        if ((i + 1) < args.length) {
                            auxString += " ";
                        }
                    }

                    auxString = auxString.replace("'", "");
                    auxGroup.getVariables().addVar(args[1], Variables.parseVariableValue(auxString));
                    sender.sendMessage(ChatColor.YELLOW + "Variable " + ChatColor.GOLD + args[1] + ChatColor.YELLOW + ":'" + ChatColor.GREEN + auxString + ChatColor.YELLOW + "' added to the group " + auxGroup.getName());

                    return true;

                case mangdelv:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangdelv <group> <variable>)");
                        return true;
                    }
                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
                    if (auxGroup.isGlobal()) {
                        sender.sendMessage(ChatColor.RED + "GlobalGroups do NOT support Info Nodes.");
                        return true;
                    }
                    // Validating permission
                    if (!auxGroup.getVariables().hasVar(args[1])) {
                        sender.sendMessage(ChatColor.RED + "The group doesn't have directly that variable!");
                        return true;
                    }
                    // Seems OK
                    auxGroup.getVariables().removeVar(args[1]);
                    sender.sendMessage(ChatColor.YELLOW + "Variable " + ChatColor.GOLD + args[1] + ChatColor.YELLOW + " removed from the group " + ChatColor.GREEN + auxGroup.getName());

                    return true;

                case manglistv:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manglistv <group>)");
                        return true;
                    }
                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
                    if (auxGroup.isGlobal()) {
                        sender.sendMessage(ChatColor.RED + "GlobalGroups do NOT support Info Nodes.");
                        return true;
                    }
				// Validating permission
                    // Seems OK
                    auxString = "";
                    for (String varKey : auxGroup.getVariables().getVarKeyList()) {
                        Object o = auxGroup.getVariables().getVarObject(varKey);
                        auxString += ChatColor.GOLD + varKey + ChatColor.WHITE + ":'" + ChatColor.GREEN + o.toString() + ChatColor.WHITE + "', ";
                    }
                    if (auxString.lastIndexOf(",") > 0) {
                        auxString = auxString.substring(0, auxString.lastIndexOf(","));
                    }
                    sender.sendMessage(ChatColor.YELLOW + "Variables of group " + auxGroup.getName() + ": ");
                    sender.sendMessage(auxString + ".");
                    auxString = "";
                    for (String grp : auxGroup.getInherits()) {
                        auxString += grp + ", ";
                    }
                    if (auxString.lastIndexOf(",") > 0) {
                        auxString = auxString.substring(0, auxString.lastIndexOf(","));
                        sender.sendMessage(ChatColor.YELLOW + "Plus all variables from groups: " + auxString);
                    }

                    return true;

                case mangcheckv:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mangcheckv <group> <variable>)");
                        return true;
                    }
                    auxGroup = dataHolder.getGroup(args[0]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[0] + "' Group doesnt exist!");
                        return true;
                    }
                    if (auxGroup.isGlobal()) {
                        sender.sendMessage(ChatColor.RED + "GlobalGroups do NOT support Info Nodes.");
                        return true;
                    }
                    // Validating permission
                    auxGroup2 = permissionHandler.nextGroupWithVariable(auxGroup, args[1]);
                    if (auxGroup2 == null) {
                        sender.sendMessage(ChatColor.RED + "The group doesn't have access to that variable!");
                    }
                    // Seems OK
                    if (auxGroup2 != null) {
                        sender.sendMessage(ChatColor.YELLOW + "The value of variable '" + ChatColor.GOLD + args[1] + ChatColor.YELLOW + "' is: '" + ChatColor.GREEN + auxGroup2.getVariables().getVarObject(args[1]).toString() + ChatColor.WHITE + "'");
                    }
                    if (!auxGroup.equals(auxGroup2)) {
                        sender.sendMessage(ChatColor.YELLOW + "And the value was inherited from group: " + ChatColor.GREEN + auxGroup2.getName());
                    }

                    return true;

                case manwhois:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manwhois <player>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }
                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    // Seems OK
                    sender.sendMessage(ChatColor.YELLOW + "Name: " + ChatColor.GREEN + auxUser.getLastName());
                    sender.sendMessage(ChatColor.YELLOW + "Group: " + ChatColor.GREEN + auxUser.getGroup().getName());
                    // Compile a list of subgroups
                    auxString = "";
                    for (String subGroup : auxUser.subGroupListStringCopy()) {
                        auxString += subGroup + ", ";
                    }
                    if (auxString.lastIndexOf(",") > 0) {
                        auxString = auxString.substring(0, auxString.lastIndexOf(","));
                        sender.sendMessage(ChatColor.YELLOW + "subgroups: " + auxString);
                    }

                    sender.sendMessage(ChatColor.YELLOW + "Overloaded: " + ChatColor.GREEN + dataHolder.isOverloaded(auxUser.getLastName()));
                    auxGroup = dataHolder.surpassOverload(auxUser.getLastName()).getGroup();
                    if (!auxGroup.equals(auxUser.getGroup())) {
                        sender.sendMessage(ChatColor.YELLOW + "Original Group: " + ChatColor.GREEN + auxGroup.getName());
                    }
                    // victim.permissions.add(args[1]);
                    return true;

                case tempadd:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/tempadd <player>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }
                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    // Validating permission
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "Can't modify player with same permissions than you, or higher.");
                        return true;
                    }
                    // Seems OK
                    overloadedUsers.putIfAbsent(dataHolder.getName().toLowerCase(), new ArrayList<>());
                    dataHolder.overloadUser(auxUser.getLastName());
                    overloadedUsers.get(dataHolder.getName().toLowerCase()).add(dataHolder.getUser(auxUser.getLastName()));
                    sender.sendMessage(ChatColor.YELLOW + "Player set to overload mode!");

                    return true;

                case tempdel:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/tempdel <player>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }
                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    // Validating permission
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player with same permissions as you, or higher.");
                        return true;
                    }
                    // Seems OK
                    overloadedUsers.putIfAbsent(dataHolder.getName().toLowerCase(), new ArrayList<>());

                    dataHolder.removeOverload(auxUser.getLastName());
                    if (overloadedUsers.get(dataHolder.getName().toLowerCase()).contains(auxUser)) {
                        overloadedUsers.get(dataHolder.getName().toLowerCase()).remove(auxUser);
                    }
                    sender.sendMessage(ChatColor.YELLOW + "Player overload mode is now disabled.");

                    return true;

                case templist:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // WORKING
                    auxString = "";
                    removeList = new ArrayList<>();
                    count = 0;
                    for (User u : overloadedUsers.get(dataHolder.getName().toLowerCase())) {
                        if (!dataHolder.isOverloaded(u.getLastName())) {
                            removeList.add(u);
                        } else {
                            auxString += u.getLastName() + ", ";
                            count++;
                        }
                    }
                    if (count == 0) {
                        sender.sendMessage(ChatColor.YELLOW + "There are no users in overload mode.");
                        return true;
                    }
                    auxString = auxString.substring(0, auxString.lastIndexOf(","));

                    overloadedUsers.putIfAbsent(dataHolder.getName().toLowerCase(), new ArrayList<>());

                    overloadedUsers.get(dataHolder.getName().toLowerCase()).removeAll(removeList);
                    sender.sendMessage(ChatColor.YELLOW + " " + count + " Users in overload mode: " + ChatColor.WHITE + auxString);

                    return true;

                case tempdelall:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // WORKING
                    removeList = new ArrayList<>();
                    count = 0;
                    count = overloadedUsers.get(dataHolder.getName().toLowerCase()).stream().filter((u) -> (dataHolder.isOverloaded(u.getLastName()))).map((u) -> {
                        dataHolder.removeOverload(u.getLastName());
                        return u;
                    }).map((_item) -> 1).reduce(count, Integer::sum);
                    if (count == 0) {
                        sender.sendMessage(ChatColor.YELLOW + "There are no users in overload mode.");
                        return true;
                    }
                    overloadedUsers.putIfAbsent(dataHolder.getName().toLowerCase(), new ArrayList<>());

                    overloadedUsers.get(dataHolder.getName().toLowerCase()).clear();
                    sender.sendMessage(ChatColor.YELLOW + " " + count + "All users in overload mode are now normal again.");

                    return true;

                case mansave:

                    boolean forced = false;

                    if ((args.length == 1) && (args[0].equalsIgnoreCase("force"))) {
                        forced = true;
                    }

                    try {
                        worldsHolder.saveChanges(forced);
                        sender.sendMessage(ChatColor.YELLOW + "All changes were saved.");
                    } catch (IllegalStateException ex) {
                        sender.sendMessage(ChatColor.RED + ex.getMessage());
                    }
                    return true;

                case manload:

                    /*
                     * Attempt to reload a specific world
                     */
                    if (args.length > 0) {

                        if (!lastError.isEmpty()) {
                            sender.sendMessage(ChatColor.RED + "All commands are locked due to an error. " + ChatColor.BOLD + "" + ChatColor.UNDERLINE + "Check plugins/groupmanager/error.log or console" + ChatColor.RESET + "" + ChatColor.RED + " and then try a '/manload'.");
                            return true;
                        }

                        auxString = "";
                        for (int i = 0; i < args.length; i++) {
                            auxString += args[i];
                            if ((i + 1) < args.length) {
                                auxString += " ";
                            }
                        }

                        isLoaded = false; // Disable Bukkit Perms update and event triggers

                        globalGroups.load();
                        worldsHolder.loadWorld(auxString);

                        sender.sendMessage("The request to reload world '" + auxString + "' was attempted.");

                        isLoaded = true;

                        BukkitPermissions.reset();

                    } else {

                        /**
                         * Reload all settings and data as no world was
                         * specified.
                         */

                        /*
                         * Attempting a fresh load.
                         */
                        onDisable(true);
                        onEnable(true);

                        sender.sendMessage("All settings and worlds were reloaded!");
                    }

                    /**
                     * Fire an event as none will have been triggered in the
                     * reload.
                     */
                    if (GroupManager.isLoaded()) {
                        GroupManager.getGMEventHandler().callEvent(GMSystemEvent.Action.RELOADED);
                    }

                    return true;

                case listgroups:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // WORKING
                    auxString = "";
                    String auxString2 = "";
                    auxString = dataHolder.getGroupList().stream().map((g) -> g.getName() + ", ").reduce(auxString, String::concat);
                    for (Group g : getGlobalGroups().getGroupList()) {
                        auxString2 += g.getName() + ", ";
                    }
                    if (auxString.lastIndexOf(",") > 0) {
                        auxString = auxString.substring(0, auxString.lastIndexOf(","));
                    }
                    if (auxString2.lastIndexOf(",") > 0) {
                        auxString2 = auxString2.substring(0, auxString2.lastIndexOf(","));
                    }
                    sender.sendMessage(ChatColor.YELLOW + "Groups Available: " + ChatColor.WHITE + auxString);
                    sender.sendMessage(ChatColor.YELLOW + "GlobalGroups Available: " + ChatColor.WHITE + auxString2);

                    return true;

                case manpromote:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manpromote <player> <group>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }
                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    auxGroup = dataHolder.getGroup(args[1]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[1] + "' Group doesnt exist!");
                        return true;
                    }
                    if (auxGroup.isGlobal()) {
                        sender.sendMessage(ChatColor.RED + "Players may not be members of GlobalGroups directly.");
                        return true;
                    }
                    // Validating permission
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player with same permissions as you, or higher.");
                        return true;
                    }
                    if (!isConsole && !isOpOverride && (permissionHandler.hasGroupInInheritance(auxGroup, senderGroup != null ? senderGroup.getName() : null))) {
                        sender.sendMessage(ChatColor.RED + "The destination group can't be the same as yours, or higher.");
                        return true;
                    }
                    if (!isConsole && !isOpOverride && (!permissionHandler.inGroup(senderUser.getLastName(), auxUser.getGroupName()) || !permissionHandler.inGroup(senderUser.getLastName(), auxGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player involving a group that you don't inherit.");
                        return true;
                    }
                    if (!permissionHandler.hasGroupInInheritance(auxUser.getGroup(), auxGroup.getName()) && !permissionHandler.hasGroupInInheritance(auxGroup, auxUser.getGroupName())) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player using groups with different heritage line.");
                        return true;
                    }
                    if (!permissionHandler.hasGroupInInheritance(auxGroup, auxUser.getGroupName())) {
                        sender.sendMessage(ChatColor.RED + "The new group must be a higher rank.");
                        return true;
                    }
                    // Seems OK
                    auxUser.setGroup(auxGroup);
                    if (!sender.hasPermission("groupmanager.notify.other") || (isConsole)) {
                        sender.sendMessage(ChatColor.YELLOW + "You changed " + auxUser.getLastName() + " group to " + auxGroup.getName() + ".");
                    }

                    return true;

                case mandemote:
                    // Validating state of sender
                    if (dataHolder == null || permissionHandler == null) {
                        if (!setDefaultWorldHandler(sender)) {
                            return true;
                        }
                    }
                    // Validating arguments
                    if (args.length != 2) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mandemote <player> <group>)");
                        return true;
                    }
                    if ((validateOnlinePlayer) && ((match = validatePlayer(args[0], sender)) == null)) {
                        return false;
                    }
                    if (match != null) {
                        auxUser = dataHolder.getUser(match.get(0));
                    } else {
                        auxUser = dataHolder.getUser(args[0]);
                    }
                    auxGroup = dataHolder.getGroup(args[1]);
                    if (auxGroup == null) {
                        sender.sendMessage(ChatColor.RED + "'" + args[1] + "' Group doesnt exist!");
                        return true;
                    }
                    if (auxGroup.isGlobal()) {
                        sender.sendMessage(ChatColor.RED + "Players may not be members of GlobalGroups directly.");
                        return true;
                    }
                    // Validating permission
                    if (!isConsole && !isOpOverride && (senderGroup != null && permissionHandler.inGroup(auxUser.getLastName(), senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player with same permissions as you, or higher.");
                        return true;
                    }
                    if (!isConsole && !isOpOverride && (permissionHandler.hasGroupInInheritance(auxGroup, senderGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "The destination group can't be the same as yours, or higher.");
                        return true;
                    }
                    if (!isConsole && !isOpOverride && (!permissionHandler.inGroup(senderUser.getLastName(), auxUser.getGroupName()) || !permissionHandler.inGroup(senderUser.getLastName(), auxGroup.getName()))) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player involving a group that you don't inherit.");
                        return true;
                    }
                    if (!permissionHandler.hasGroupInInheritance(auxUser.getGroup(), auxGroup.getName()) && !permissionHandler.hasGroupInInheritance(auxGroup, auxUser.getGroupName())) {
                        sender.sendMessage(ChatColor.RED + "You can't modify a player using groups with different inheritage line.");
                        return true;
                    }
                    if (permissionHandler.hasGroupInInheritance(auxGroup, auxUser.getGroupName())) {
                        sender.sendMessage(ChatColor.RED + "The new group must be a lower rank.");
                        return true;
                    }
                    // Seems OK
                    auxUser.setGroup(auxGroup);
                    if (!sender.hasPermission("groupmanager.notify.other") || (isConsole)) {
                        sender.sendMessage(ChatColor.YELLOW + "You changed " + auxUser.getLastName() + " group to " + auxGroup.getName() + ".");
                    }

                    return true;

                case mantogglevalidate:
                    validateOnlinePlayer = !validateOnlinePlayer;
                    sender.sendMessage(ChatColor.YELLOW + "Validate if player is online, now set to: " + Boolean.toString(validateOnlinePlayer));
                    if (!validateOnlinePlayer) {
                        sender.sendMessage(ChatColor.GOLD + "From now on you can edit players that are not connected... BUT:");
                        sender.sendMessage(ChatColor.LIGHT_PURPLE + "From now on you should type the whole name of the player, correctly.");
                    }
                    return true;
                case mantogglesave:
                    if (scheduler == null) {
                        enableScheduler();
                        sender.sendMessage(ChatColor.YELLOW + "The auto-saving is enabled!");
                    } else {
                        disableScheduler();
                        sender.sendMessage(ChatColor.YELLOW + "The auto-saving is disabled!");
                    }
                    return true;
                case manworld:
                    auxString = selectedWorlds.get(sender.getName());
                    if (auxString != null) {
                        sender.sendMessage(ChatColor.YELLOW + "You have the world '" + dataHolder.getName() + "' in your selection.");
                    } else {
                        if (dataHolder == null) {
                            sender.sendMessage(ChatColor.YELLOW + "There is no world selected. And no world is available now.");
                        } else {
                            sender.sendMessage(ChatColor.YELLOW + "You don't have a world in your selection..");
                            sender.sendMessage(ChatColor.YELLOW + "Working with the direct world where your player is.");
                            sender.sendMessage(ChatColor.YELLOW + "Your world now uses permissions of world name: '" + dataHolder.getName() + "' ");
                        }
                    }

                    return true;

                case manselect:
                    if (args.length < 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/manselect <world>)");
                        sender.sendMessage(ChatColor.YELLOW + "Worlds available: ");
                        ArrayList<OverloadedWorldHolder> worlds = worldsHolder.allWorldsDataList();
                        auxString = "";
                        for (int i = 0; i < worlds.size(); i++) {
                            auxString += worlds.get(i).getName();
                            if ((i + 1) < worlds.size()) {
                                auxString += ", ";
                            }
                        }
                        sender.sendMessage(ChatColor.YELLOW + auxString);
                        return false;
                    }
                    auxString = "";
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] == null) {
                            logger.warning("Bukkit gave invalid arguments array! Cmd: " + cmd.getName() + " args.length: " + args.length);
                            return false;
                        }
                        auxString += args[i];
                        if (i < (args.length - 1)) {
                            auxString += " ";
                        }
                    }
                    dataHolder = worldsHolder.getWorldData(auxString);
                    permissionHandler = dataHolder.getPermissionsHandler();
                    selectedWorlds.put(sender.getName(), dataHolder.getName());
                    sender.sendMessage(ChatColor.YELLOW + "You have selected world '" + dataHolder.getName() + "'.");

                    return true;

                case manclear:
                    if (args.length != 0) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count!");
                        return false;
                    }
                    selectedWorlds.remove(sender.getName());
                    sender.sendMessage(ChatColor.YELLOW + "You have removed your world selection. Working with current world(if possible).");

                    return true;

                case mancheckw:
                    if (args.length < 1) {
                        sender.sendMessage(ChatColor.RED + "Review your arguments count! (/mancheckw <world>)");
                        sender.sendMessage(ChatColor.YELLOW + "Worlds available: ");
                        ArrayList<OverloadedWorldHolder> worlds = worldsHolder.allWorldsDataList();
                        auxString = "";
                        for (int i = 0; i < worlds.size(); i++) {
                            auxString += worlds.get(i).getName();
                            if ((i + 1) < worlds.size()) {
                                auxString += ", ";
                            }
                        }
                        sender.sendMessage(ChatColor.YELLOW + auxString);
                        return false;
                    }

                    auxString = "";
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] == null) {
                            logger.warning("Bukkit gave invalid arguments array! Cmd: " + cmd.getName() + " args.length: " + args.length);
                            return false;
                        }
                        auxString += args[i];
                        if (i < (args.length - 1)) {
                            auxString += " ";
                        }
                    }
                    dataHolder = worldsHolder.getWorldData(auxString);

                    sender.sendMessage(ChatColor.YELLOW + "You have selected world '" + dataHolder.getName() + "'.");
                    sender.sendMessage(ChatColor.YELLOW + "This world is using the following data files..");
                    sender.sendMessage(ChatColor.YELLOW + "Groups:" + ChatColor.GREEN + " " + dataHolder.getGroupsFile().getAbsolutePath());
                    sender.sendMessage(ChatColor.YELLOW + "Users:" + ChatColor.GREEN + " " + dataHolder.getUsersFile().getAbsolutePath());

                    return true;

                default:
                    break;
            }
        }
        sender.sendMessage(ChatColor.RED + "You are not allowed to use that command.");
        return true;
    }

    /**
     * Checks if a permission exists and of a lower or same priority.
     */
    private boolean checkPermissionExists(CommandSender sender, String newPerm, PermissionCheckResult oldPerm, String type) {

        if (newPerm.startsWith("+")) {
            if (oldPerm.resultType.equals(PermissionCheckResult.Type.EXCEPTION)) {
                sender.sendMessage(ChatColor.RED + "The " + type + " already has direct access to that permission.");
                sender.sendMessage(ChatColor.RED + "Node: " + oldPerm.accessLevel);
                return true;
            }
        } else if (newPerm.startsWith("-")) {
            if (oldPerm.resultType.equals(PermissionCheckResult.Type.EXCEPTION)) {
                sender.sendMessage(ChatColor.RED + "The " + type + " already has an exception for this node.");
                sender.sendMessage(ChatColor.RED + "Node: " + oldPerm.accessLevel);
                return true;
            } else if (oldPerm.resultType.equals(PermissionCheckResult.Type.NEGATION)) {
                sender.sendMessage(ChatColor.RED + "The " + type + " already has a matching negated node.");
                sender.sendMessage(ChatColor.RED + "Node: " + oldPerm.accessLevel);
                return true;
            }
        } else {
            if (oldPerm.resultType.equals(PermissionCheckResult.Type.EXCEPTION)) {
                sender.sendMessage(ChatColor.RED + "The " + type + " already has an exception for this node.");
                sender.sendMessage(ChatColor.RED + "Node: " + oldPerm.accessLevel);
            } else if (oldPerm.resultType.equals(PermissionCheckResult.Type.NEGATION)) {
                sender.sendMessage(ChatColor.RED + "The " + type + " already has a matching negated node.");
                sender.sendMessage(ChatColor.RED + "Node: " + oldPerm.accessLevel);
            } else if (oldPerm.resultType.equals(PermissionCheckResult.Type.FOUND)) {
                sender.sendMessage(ChatColor.RED + "The " + type + " already has direct access to that permission.");
                sender.sendMessage(ChatColor.RED + "Node: " + oldPerm.accessLevel);

                // Since not all plugins define wildcard permissions, allow setting the permission anyway if the permissions dont match exactly.
                return (oldPerm.accessLevel.equalsIgnoreCase(newPerm));
            }
        }
        return false;
    }

    /**
     * Sets up the default world for use.
     */
    private boolean setDefaultWorldHandler(CommandSender sender) {

        dataHolder = worldsHolder.getWorldData(worldsHolder.getDefaultWorld().getName());
        permissionHandler = dataHolder.getPermissionsHandler();

        if ((dataHolder != null) && (permissionHandler != null)) {
            selectedWorlds.put(sender.getName(), dataHolder.getName());
            sender.sendMessage(ChatColor.RED + "Couldn't retrieve your world. Default world '" + worldsHolder.getDefaultWorld().getName() + "' selected.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Couldn't retrieve your world. World selection is needed.");
        sender.sendMessage(ChatColor.RED + "Use /manselect <world>");
        return false;

    }

    /**
     * Send confirmation of a group change. using permission nodes...
     *
     * groupmanager.notify.self groupmanager.notify.other
     *
     * @param name
     * @param msg
     */
    @SuppressWarnings("ConstantConditions")
    public static void notify(String name, String msg) {

        Player player = Bukkit.getServer().getPlayerExact(name);

        Bukkit.getServer().getOnlinePlayers().forEach(p -> {
            if (!p.equals(player)) {
                if (p.hasPermission("groupmanager.notify.other")) {
                    p.sendMessage(ChatColor.YELLOW + name + " was" + msg);
                }
            } else if ((player != null) && ((player.hasPermission("groupmanager.notify.self")) || (player.hasPermission("groupmanager.notify.other")))) {
                player.sendMessage(ChatColor.YELLOW + "You were" + msg);
            }
        });

    }

    /**
     * Load a List of players matching the name given. If none online, check
     * Offline.
     *
     * @param playerName, sender
     * @return true if a single match is found
     */
    private List<String> validatePlayer(String playerName, CommandSender sender) {

        List<Player> players;
        List<String> match = new ArrayList<>();

        players = this.getServer().matchPlayer(playerName);
        if (players.isEmpty()) {
            // Check for an offline player (exact match).
            if (Arrays.asList(this.getServer().getOfflinePlayers()).contains(Bukkit.getOfflinePlayer(playerName))) {
                match.add(playerName);
            } else {
                // look for partial matches
                for (OfflinePlayer offline : this.getServer().getOfflinePlayers()) {
                    if (offline.getName().toLowerCase().startsWith(playerName.toLowerCase())) {
                        match.add(offline.getName());
                    }
                }
            }

        } else {
            match.addAll(players.stream().map(HumanEntity::getName).collect(Collectors.toList()));
        }

        if (match.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return null;
        } else if (match.size() > 1) {
            sender.sendMessage(ChatColor.RED + "Too many matches found! (" + match.toString() + ")");
            return null;
        }

        return match;

    }

    /**
     * @return the config
     */
    public GMConfiguration getGMConfig() {

        return config;
    }

    /**
     * @return the backupFolder
     */
    public File getBackupFolder() {

        return backupFolder;
    }

    public static GlobalGroups getGlobalGroups() {

        return globalGroups;

    }

    public static GroupManagerEventHandler getGMEventHandler() {

        return GMEventHandler;
    }

    public static void setGMEventHandler(GroupManagerEventHandler gMEventHandler) {

        GMEventHandler = gMEventHandler;
    }
}
