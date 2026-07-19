package net.vulkanmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class Initializer implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("Raydium");

	private static String VERSION;
	public static Config CONFIG;

	@Override
	public void onInitializeClient() {
		VERSION = FabricLoader.getInstance()
				.getModContainer("raydium")      // mod id переименован vulkanmod -> raydium
				.get()
				.getMetadata()
				.getVersion().getFriendlyString();

		LOGGER.info("== Raydium (based on VulkanMod) ==");

		var configPath = FabricLoader.getInstance()
				.getConfigDir()
				.resolve("vulkanmod_settings.json");

		CONFIG = loadConfig(configPath);

		// === RT PATCH === применить сохранённые настройки трассировки (страница «Трассировка»).
		// ⚠️ DLSS обязан быть решён ДО создания устройства: от него зависит список расширений.
		// M8.125: фолбэк отложен — RT намертво следует за «Шейдерами». Миграция конфига:
		// после тестов фолбэка мог остаться rtEnabled=false при shadersEnabled=true —
		// с некликабельной ручкой RT из этого состояния было бы не выбраться.
		CONFIG.rtEnabled = CONFIG.shadersEnabled;
		net.vulkanmod.vulkan.rt.RtScreen.enabled = CONFIG.rtEnabled;
		// M8.156 МИГРАЦИЯ: тумблер «DLSS» заменён двумя парами «тумблер + выбор». У кого в конфиге
		// остался старый флаг выключенным — переносим его, иначе человек молча получил бы DLSS обратно.
		if (!CONFIG.dlss && CONFIG.rtDenoiserOn && CONFIG.rtUpscalerOn) {
			CONFIG.rtDenoiserOn = false;
			CONFIG.rtUpscalerOn = false;
			CONFIG.dlss = true;                       // флаг отработал, дальше он не значит ничего
			CONFIG.write();
		}
		net.vulkanmod.vulkan.rt.RtDlss.enabled = CONFIG.dlssActive();

		Platform.init();

		Renderer.register(VulkanModRenderer.INSTANCE);

		UpdateChecker.checkForUpdates();
	}

	private static Config loadConfig(Path path) {
        return Config.load(path);
	}

	public static String getVersion() {
		return VERSION;
	}
}
