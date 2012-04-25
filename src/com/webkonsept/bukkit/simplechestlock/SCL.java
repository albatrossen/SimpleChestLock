package com.webkonsept.bukkit.simplechestlock;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.webkonsept.bukkit.simplechestlock.listener.SCLBlockListener;
import com.webkonsept.bukkit.simplechestlock.listener.SCLEntityListener;
import com.webkonsept.bukkit.simplechestlock.listener.SCLPlayerListener;
import com.webkonsept.bukkit.simplechestlock.listener.SCLWorldListener;
import com.webkonsept.bukkit.simplechestlock.locks.LimitHandler;
import com.webkonsept.bukkit.simplechestlock.locks.SCLItem;
import com.webkonsept.bukkit.simplechestlock.locks.SCLList;
import com.webkonsept.bukkit.simplechestlock.locks.TrustHandler;

public class SCL extends JavaPlugin {
	private static final Logger log = Logger.getLogger("Minecraft");

    protected static final String pluginName = "SimpleChestLock";
    protected static String pluginVersion = "???";

	protected static boolean verbose = false;
	public boolean lockpair = true;
	public ItemStack key;
	public ItemStack comboKey;
	public boolean useKeyData = false;
    public boolean consumeKey = false;
	public boolean openMessage = true;
	public boolean usePermissionsWhitelist = false;
	public boolean whitelistMessage = true;
	public boolean lockedChestsSuck = false;
	public int suckRange = 3;
	public TrustHandler trustHandler;
	public LimitHandler limitHandler;
	public boolean useLimits = false;
	public int suckInterval = 100;
	public boolean suckEffect = true;
	
	public final Messaging messaging = new Messaging(3000);
	
	protected Server server = null;
	
	// Intended to hold the material in question and a boolean of weather or not it's double-lockable (like a double chest)
	public final HashMap<Material,Boolean> lockable = new HashMap<Material,Boolean>();
	
	// Intended to hold the materials of items/blocked that can also be activated by left-click
	public final HashSet<Material> leftLocked = new HashSet<Material>();
	
	// Holding the valid locations for a multi-lockable block
	public final HashSet<Material> lockIncludeVertical = new HashSet<Material>();
	
	// Okay for the "sucks items" feature (Item containers only plx!)
	public final HashSet<Material> canSuck = new HashSet<Material>();
	
	// The "Lock as" feature!
	public final HashMap<String,String> locksAs = new HashMap<String,String>();
	
	private final SCLPlayerListener 	playerListener 	= new SCLPlayerListener(this);
	private final SCLBlockListener 	blockListener 	= new SCLBlockListener(this);
	private final SCLEntityListener 	entityListener 	= new SCLEntityListener(this);
	private final SCLWorldListener	worldListener	= new SCLWorldListener(this);
	public final SCLList			chests			= new SCLList(this);
	
	@Override
	public void onDisable() {
		chests.save("Chests.txt");
		out("Disabled!");
		getServer().getScheduler().cancelTasks(this);
	}

	@Override
	public void onEnable() {
        pluginVersion = getDescription().getVersion();
	    loadConfig();
		setupLockables();
		
		trustHandler = new TrustHandler(this);
		limitHandler = new LimitHandler(this);

		server = getServer();
		chests.load("Chests.txt");
		PluginManager pm = getServer().getPluginManager();
		
		pm.registerEvents(playerListener,this);
		pm.registerEvents(blockListener,this);
		pm.registerEvents(entityListener,this);
		pm.registerEvents(worldListener,this);
		
		if (lockedChestsSuck){
			server.getScheduler().scheduleSyncRepeatingTask(this,chests, suckInterval, suckInterval);
		}
		// out("Enabled!"); // Done by Bukkit now
	}
	
	public boolean onCommand(CommandSender sender, Command command, String commandLabel, String[] args) {
		
		if ( ! this.isEnabled() ) return false;
		
		boolean success = false;
		boolean isPlayer = false;
		Player player = null;
		
		if (sender instanceof Player){
			isPlayer = true;
			player = (Player)sender;
		}

		if (command.getName().equalsIgnoreCase("scl")){
			if (args.length == 0){
				success = false;  // Automagically prints the usage.
			}
			else if (args.length >= 1){
				if (args[0].equalsIgnoreCase("reload")){
					success = true;  // This is a valid command.
					if ( !isPlayer  || permit(player, "simplechestlock.command.reload")){
						try {
							getConfig().load(new File(getDataFolder(),"config.yml"));
							this.loadConfig();
						} catch (FileNotFoundException e) {
							crap("Configuration file went away!");
						} catch (IOException e) {
							e.printStackTrace();
							crap("IOException while reading the config file!  "+e.getMessage());
						} catch (InvalidConfigurationException e) {
							e.printStackTrace();
							crap("Looks like you suck at YAML.  Try again.");
						}
						String saveFile = "Chests.txt";
						if (args.length == 2){
							saveFile = args[1];
						}
						chests.load(saveFile);
						server.getScheduler().cancelTasks(this);
						if (lockedChestsSuck){
							server.getScheduler().scheduleSyncRepeatingTask(this,chests, 100, 100);
						}
						sender.sendMessage(ChatColor.GREEN+"Successfully reloaded configuration and locks from "+saveFile);
						
					}
					else {
						sender.sendMessage(ChatColor.RED+"[SimpleChestLock] Sorry, permission denied!");
					}
				}
				else if (args[0].equalsIgnoreCase("as")){
					success = true;
					if (args.length == 2){
						if (!isPlayer){
							sender.sendMessage("Sorry mr. Console, you can't lock as anyone.  How will you swing the stick?");
						}
						else if (permit(player, "simplechestlock.command.as")){
							locksAs.put(player.getName(),args[1]);
							sender.sendMessage(ChatColor.RED+"[SimpleChestLock] Locking chests for "+args[1]);
						}
						else {
							sender.sendMessage(ChatColor.RED+"[SimpleChestLock] Sorry, permission denied!");
						}
					}
					else if (args.length == 1){
						if (locksAs.containsKey(player.getName())){
							locksAs.remove(player.getName());
						}
						sender.sendMessage(ChatColor.GREEN+"[SimpleChestLock] Locking chests for yourself");
					}
					else if (args.length > 2){
						sender.sendMessage(ChatColor.YELLOW+"[SimpleChestLock] Argument amount mismatch.  /scl as <name>");
					}
				}
				else if (args[0].equalsIgnoreCase("save")){
					success = true;
					if (!isPlayer || permit(player,"simplechestlock.command.save")){
						String saveFile = "Chests.txt";
						if (args.length == 2){
							saveFile = args[1];
						}
						chests.save(saveFile);
						sender.sendMessage(ChatColor.GREEN+"Saved locks to "+saveFile);
					}
					else {
						sender.sendMessage(ChatColor.RED+"[SimpleChestLock] Sorry, permission denied!");
					}
				}
				else if (args[0].equalsIgnoreCase("limit")){
				    success = true;
				    if (!isPlayer){
				        sender.sendMessage("Mr. Console, you can't lock anything at all, so your limit is -1!");
				    }
				    else if (!useLimits){
				        sender.sendMessage(ChatColor.GOLD+"This server has no lock limits");
				    }
				    else if (permit(player,"simplechestlock.nolimit")){
				        sender.sendMessage(ChatColor.GOLD+"You are excempt from lock limits");
				    }
				    else {
				        sender.sendMessage(ChatColor.GREEN+limitHandler.usedString(player));
				    }
				}
				else if (args[0].equalsIgnoreCase("status")){
					success = true;
					if (!isPlayer || permit(player,"simplechestlock.command.status")){
						HashMap<String,Integer> ownership = new HashMap<String,Integer>();
						int total = 0;
						for (SCLItem item : chests.list.values()){
							Integer owned = ownership.get(item.getOwner());
							total++;
							if (owned == null){
								ownership.put(item.getOwner(),1);
							}
							else {
								ownership.put(item.getOwner(),++owned);
							}
						}
						
						for (String playerName : ownership.keySet()){
							Integer owned = ownership.get(playerName);
							if (owned == null){
								owned = 0;
							}
							sender.sendMessage(playerName+": "+owned);
						}
						sender.sendMessage("Total: "+total);
					}
				}
				else if (args[0].equalsIgnoreCase("trust")){
				    if (isPlayer){
				        if (permit(player,"simplechestlock.command.trust")){
				            trustHandler.parseCommand(player,args);
				        }
				    }
				    success = true;
				}
				else if (args[0].equalsIgnoreCase("list")){
					success = true;
					if (!isPlayer || permit(player, "simplechestlock.command.list")){
						for (SCLItem item : chests.list.values()){
							sender.sendMessage(item.getLocation().toString());
						}
					}
				}
				else if (args[0].equalsIgnoreCase("getkey")){
				    success = true;
				    if (isPlayer){
				        if (permit(player, "simplechestlock.command.getkey")){
				            player.getInventory().addItem(key.clone());
				            player.sendMessage(ChatColor.GREEN+"One key coming right up!");
				        }
				        else {
				            player.sendMessage(ChatColor.RED+"Access denied");
				        }
				    }
				    else {
				        sender.sendMessage("Sorry, Mr. Console, you can't carry keys.");
				    }
				}
				else if (args[0].equalsIgnoreCase("getcombokey")){
				    success = true;
                    if (isPlayer){
                        if (permit(player, "simplechestlock.command.getcombokey")){
                            player.getInventory().addItem(comboKey.clone());
                            player.sendMessage(ChatColor.GREEN+"One combokey coming right up!");
                        }
                        else {
                            player.sendMessage(ChatColor.RED+"Access denied");
                        }
                    }
                    else {
                        sender.sendMessage("Sorry, Mr. Console, you can't carry keys.");
                    }				    
				}
			}
		}
		else {
			sender.sendMessage(ChatColor.RED+"Command is deprecated, try /scl");
		}
		return success;
	}
	public static boolean permit(Player player,String[] permissions){
		
		if (player == null) return false;
		if (permissions == null) return false;
		String playerName = player.getName();
		boolean permit = false;
		for (String permission : permissions){
			permit = player.hasPermission(permission);
			if (permit){
				verbose("Permission granted: " + playerName + "->(" + permission + ")");
				break;
			}
			else {
				verbose("Permission denied: " + playerName + "->(" + permission + ")");
			}
		}
		return permit;
		
	}
	public static boolean permit(Player player,String permission){
		return permit(player,new String[]{permission});
	}
	public static void out(String message) {
        log.info("[" + pluginName + " v" + pluginVersion + "] " + message);
	}
	public void crap(String message){
        log.severe("[" + pluginName + " v" + pluginVersion + "] " + message);
	}
	public static void verbose(String message){
		if (!verbose){ return; }
		log.info("[" + pluginName + " v" + pluginVersion + " VERBOSE] " + message);
	}
	public static String plural(int number) {
		if (number == 1){
			return "";
		}
		else {
			return "s";
		}
	}
	private void setupLockables() {
		lockable.clear();
		// DEFAULT VALUES
		lockable.put(Material.CHEST,true);
		lockable.put(Material.DISPENSER,false);
		lockable.put(Material.JUKEBOX,false);
		lockable.put(Material.ENCHANTMENT_TABLE,false);
		lockable.put(Material.BREWING_STAND,false);
		
		// Requested to be lockable, even though it can't store stuff
		lockable.put(Material.WORKBENCH,false);
		
		//NOTE:  If double locking is enabled for furnaces, remember that a furnace and a burning furnace are NOT the same material!
		// That means that double locking, which tests the neighboring blocks for .equals() on the material, won't work if one is burning. 
		lockable.put(Material.FURNACE,false);
		lockable.put(Material.BURNING_FURNACE,false);
		
		// Levers, buttons and doors are special:  You can activate them with a left click.
		// Hence, we have to lock interaction via LMB as well, making them "leftLocked"
		
		lockable.put(Material.LEVER,false);
		leftLocked.add(Material.LEVER);
		lockable.put(Material.STONE_BUTTON,false);
		leftLocked.add(Material.STONE_BUTTON);
		lockable.put(Material.TRAP_DOOR, false);
		leftLocked.add(Material.TRAP_DOOR);
		
		// WTH, this doesn't seem to work?!
		lockable.put(Material.FENCE_GATE, false);
		leftLocked.add(Material.FENCE_GATE);
		
		// And now:  Pressure plates!
		lockable.put(Material.STONE_PLATE,false);
		lockable.put(Material.WOOD_PLATE,false);
		
		// Doors are lockable, leftLocked AND vertically speaking TWO blocks.
		// This makes them rather complex to lock...
		lockable.put(Material.WOODEN_DOOR, true);
		leftLocked.add(Material.WOODEN_DOOR);
		lockIncludeVertical.add(Material.WOODEN_DOOR);
		
		
		// Some types will "suck" in items, if enabled
		canSuck.add(Material.CHEST);
		canSuck.add(Material.DISPENSER);
		
		
		// The associated permissions
		verbose("Preparing permissions:");
	    Permission allBlocksPermission = new Permission("simplechestlock.locktype.*");
        for (Material mat : lockable.keySet()){
            if (mat.isBlock()){
                String permissionName = "simplechestlock.locktype."+mat.toString().toLowerCase();
                verbose("   -> Preparing permission " + permissionName);
                Permission thisBlockPermission = new Permission(permissionName,PermissionDefault.OP);
                //getServer().getPluginManager().addPermission(allBlocksPermission);
                thisBlockPermission.addParent(allBlocksPermission, true);
            }
        }
        getServer().getPluginManager().addPermission(allBlocksPermission);
	}
	public boolean canLock (Block block){
		if (block == null) return false;
		Material material = block.getType();
		return lockable.containsKey(material);
	}
	public boolean canDoubleLock (Block block){
		if (block == null) return false;
		Material material = block.getType();
		if (lockable.containsKey(material)){
			return lockable.get(material);
		}
		else {
			return false;
		}
	}
	public void loadConfig() {
		File configFile = new File(this.getDataFolder(),"config.yml");
		File oldConfigFile = new File(this.getDataFolder(),"settings.yml");
		getConfig().options().copyDefaults(true);
		getConfig().addDefaults(new HashMap<String,Object>(){
			{
				put("verbose",false);
				put("useLimits",false);
				put("key",280);
				put("keyDurability",0);
				put("comboKey",352);
				put("comboKeyDurability",0);
				put("useKeyDurability",false);
                put("consumeKey",false);
				
				put("lockpair",true);
				put("usePermissionsWhitelist",false);
				put("whitelistMessage",true);
				put("openMessage",true);
				
                put("lockedChestsSuck",false);
                put("suckRange",3);
                put("suckInterval",100);
                put("suckEffect",true);
				
			}
		});
		
		if (oldConfigFile.exists()){
			out("Old configuration file found, attempting to move to one!");
			try {
				getConfig().load(oldConfigFile);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				crap("Uh, old config file went away.");
			} catch (IOException e) {
				e.printStackTrace();
				crap("Permissions issues on old config file, perhaps?  Whatever, it's gone.");
			} catch (InvalidConfigurationException e) {
				e.printStackTrace();
				crap("Old config file isn't valid, and will be clobbered.");
			}
			oldConfigFile.delete();
		}
		verbose = getConfig().getBoolean("verbose", false);
	    lockpair = getConfig().getBoolean("lockpair", true);
	    usePermissionsWhitelist = getConfig().getBoolean("usePermissionsWhitelist",false);
	    whitelistMessage = getConfig().getBoolean("whitelistMessage",true);
	    openMessage = getConfig().getBoolean("openMessage", true);
	    
	    useLimits = getConfig().getBoolean("useLimits",false);
		
		Integer keyInt = getConfig().getInt("key",280); // Stick
		Integer keyDurability = getConfig().getInt("keyDurability",0);
		Integer comboKeyInt = getConfig().getInt("comboKey",352); // Bone
		Integer comboKeyDurability = getConfig().getInt("comboKeyDurability",0);
		useKeyData = getConfig().getBoolean("useKeyDurability",false);

        consumeKey = getConfig().getBoolean("consumeKey",false);
		
		lockedChestsSuck = getConfig().getBoolean("lockedChestsSuck",false);
		suckRange = getConfig().getInt("suckRange",3);
		suckInterval = getConfig().getInt("suckInterval",100);
		suckEffect = getConfig().getBoolean("suckEffect",true);
		
		Material keyMaterial = Material.getMaterial(keyInt);
		Material comboKeyMaterial = Material.getMaterial(comboKeyInt);
		if (keyMaterial == null){
			keyMaterial = Material.STICK;
			useKeyData = false;
			this.crap("OY!  Material ID "+keyInt+" is not a real material.  Falling back to using STICK (ID 280) for the key.");
		}
		if (comboKeyMaterial == null){
			comboKeyMaterial = Material.BONE;
			useKeyData = false;
			this.crap("OY!  Materail ID "+comboKeyInt+" is not a real material. Falling back to using BONE (ID 352) for the combo key.");
		}
		
		key = new ItemStack(keyMaterial);
		key.setAmount(1);
		comboKey = new ItemStack(comboKeyMaterial);
		comboKey.setAmount(1);
		
		if (useKeyData){
		    key.setDurability((short)(int)keyDurability);
		    comboKey.setDurability((short)(int)comboKeyDurability);
		}
		
		if (!configFile.exists()){
		    saveConfig();
		    /*
			try {
				getConfig().save(configFile);
			} catch (IOException e) {
				e.printStackTrace();
				this.crap("IOError while creating config file: "+e.getMessage());
			}
			*/
		}
		if (trustHandler != null){
		    trustHandler.loadFromConfig();
		}
		if (limitHandler != null){
		    limitHandler.loadFromConfig();
		}
	}
	public boolean toolMatch (ItemStack candidate1,ItemStack candidate2){
	    if (candidate1 == null || candidate2 == null){
	        return false;
	    }
	    else return candidate1.getType().equals(candidate2.getType())
                && candidate1.getData().equals(candidate2.getData());
	}
}
