/*
 * {{ SafeChat }}
 * Copyright (C) 2024 CMarco
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package top.cmarco.safechat;

import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.Server;
import org.bukkit.command.CommandMap;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import studio.thevipershow.vtc.PluginConfigurationsData;
import studio.thevipershow.vtc.PluginsConfigurationsManager;
import top.cmarco.safechat.chat.check.types.*;
import top.cmarco.safechat.config.blacklist.BlacklistConfig;
import top.cmarco.safechat.api.checks.ChecksContainer;
import top.cmarco.safechat.chat.listeners.ChatListener;
import top.cmarco.safechat.commands.SafeChatCommand;
import top.cmarco.safechat.config.Configurations;
import top.cmarco.safechat.config.address.AddressConfig;
import top.cmarco.safechat.config.checks.CheckConfig;
import top.cmarco.safechat.config.localization.Localization;
import top.cmarco.safechat.config.messages.MessagesConfig;
import top.cmarco.safechat.debug.Debugger;
import top.cmarco.safechat.persistence.SafeChatHibernate;

import java.util.Objects;

@SuppressWarnings("unused")
public final class SafeChat extends JavaPlugin {

    public static final short PLUGIN_ID = 9876;
    private static final String VAULT_NAME = "Vault";
    public static Localization localization;

    private PluginsConfigurationsManager configManager;
    private PluginConfigurationsData<SafeChat> configData;

    private ChecksContainer checksContainer;
    private Economy economy;
    private ChatListener chatListener;
    private Metrics metrics;
    private SafeChatHibernate safeChatHibernate;
    private Debugger debugger;

    private SafeChatCommand safechatCommand;

    public static Localization getLocale() {
        return localization;
    }

    private void setupMetrics() {
        metrics = new Metrics(this, PLUGIN_ID);
    }

    private boolean setupEconomy() {
        Server server = getServer();
        if (server.getPluginManager().getPlugin(VAULT_NAME) == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> economyRsp = server.getServicesManager().getRegistration(Economy.class);
        if (economyRsp == null) {
            return false;
        }
        economy = economyRsp.getProvider();
        return economy != null;
    }

    @SuppressWarnings("unchecked")
    private void setupConfigs() {
        configManager = PluginsConfigurationsManager.getInstance();
        PluginConfigurationsData<SafeChat> pluginData = Objects.requireNonNull(configManager.loadPluginData(this));
        this.configData = pluginData;
        pluginData.setConsoleDebuggingInfo(true);
        pluginData.loadAllConfigs(Configurations.class);
        pluginData.exportAndLoadAllLoadedConfigs(false);
        localization.loadTranslation(Objects.requireNonNull(pluginData.getConfig(Configurations.MESSAGES)));
    }

    @SuppressWarnings("unchecked")
    private void setupChecksContainer() {
        checksContainer = ChecksContainer.getInstance(this);
        PluginConfigurationsData<SafeChat> pluginConfigurationsData = Objects.requireNonNull(getConfigData());
        MessagesConfig messagesConfig = Objects.requireNonNull(pluginConfigurationsData.getConfig(Configurations.MESSAGES));
        CheckConfig checkConfig = Objects.requireNonNull(pluginConfigurationsData.getConfig(Configurations.CHECKS_SETTINGS));
        AddressConfig addressConfig = Objects.requireNonNull(pluginConfigurationsData.getConfig(Configurations.ADDRESS));
        BlacklistConfig blacklistConfig = Objects.requireNonNull(pluginConfigurationsData.getConfig(Configurations.BLACKLIST));

        AddressCheck addressCheck = new AddressCheck(addressConfig, checkConfig, messagesConfig);
        FloodCheck floodCheck = new FloodCheck(checkConfig, messagesConfig);
        RepetitionCheck repetitionCheck = new RepetitionCheck(checkConfig, messagesConfig);
        WordsBlacklistCheck wordsBlacklistCheck = new WordsBlacklistCheck(blacklistConfig, checkConfig, messagesConfig);
        CapsCheck capsCheck = new CapsCheck(checkConfig, messagesConfig);

        checksContainer.register(addressCheck);
        checksContainer.register(floodCheck);
        checksContainer.register(repetitionCheck);
        checksContainer.register(wordsBlacklistCheck);
        checksContainer.register(capsCheck);
    }

    private void setupListeners() {
        PluginManager pManager = getServer().getPluginManager();
        chatListener = new ChatListener(safeChatHibernate, checksContainer);
        pManager.registerEvents(chatListener, this);
    }

    private void setupCommands() {
        safechatCommand = new SafeChatCommand(this);
        try {
            CommandMap commandMap = SafeChatUtils.getCommandMap();
            commandMap.register("safechat", safechatCommand);
        } catch (Exception e) {
            getLogger().warning("Could not register the \"safechat\" command.");
        }
    }

    @SuppressWarnings("unchecked")
    private void setupHibernate() {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(this.getClassLoader());
        safeChatHibernate = new SafeChatHibernate(Objects.requireNonNull(configData.getConfig(Configurations.DATABASE_SETTINGS)), this, this.getClassLoader());
        safeChatHibernate.setupHibernateSQLMapping();
        safeChatHibernate.setupSessionFactory();
        safeChatHibernate.setupPlayerDataManager();
        Thread.currentThread().setContextClassLoader(old);
    }

    private void setupDebugger() {
        debugger = Debugger.getInstance(getLogger());
    }

    @Override
    public void onEnable() {
        localization = new Localization();
        setupMetrics();
        setupDebugger();
        setupConfigs();
        if (!setupEconomy()) {
            getLogger().warning("Vault not present, cannot use economy functionalities.");
        }
        setupHibernate();
        setupChecksContainer();
        setupCommands();
        setupListeners();
    }

    @Override
    public void onDisable() {
        if (safeChatHibernate != null)
            safeChatHibernate.shutdown();

        unregisterCommands();
    }

    private void unregisterCommands() {
        try {
            final CommandMap map = SafeChatUtils.getCommandMap();
            if (!safechatCommand.unregister(map)) {
                getLogger().warning("Could not unregister \"safechat\" command");
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            getLogger().warning("Could not get command map!");
        }
    }

    @NotNull
    public PluginsConfigurationsManager getConfigManager() {
        return configManager;
    }

    @NotNull
    public PluginConfigurationsData<SafeChat> getConfigData() {
        return configData;
    }

    @NotNull
    public ChecksContainer getChecksContainer() {
        return checksContainer;
    }

    @NotNull
    public Economy getEconomy() {
        return economy;
    }

    @NotNull
    public ChatListener getChatListener() {
        return chatListener;
    }

    @NotNull
    public Metrics getMetrics() {
        return metrics;
    }

    @NotNull
    public SafeChatHibernate getSafeChatHibernate() {
        return safeChatHibernate;
    }

    public Debugger getDebugger() {
        return debugger;
    }
}
