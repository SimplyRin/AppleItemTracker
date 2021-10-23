package net.simplyrin.appleitemtracker;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Scanner;

import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.io.IOUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.simplyrin.appleitemtracker.utils.ThreadPool;
import net.simplyrin.config.Config;
import net.simplyrin.config.Configuration;
import net.simplyrin.rinstream.RinStream;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.User;
import twitter4j.auth.AccessToken;
import twitter4j.auth.RequestToken;

/**
 * Created by SimplyRin on 2021/10/19.
 *
 * Copyright (c) 2021 SimplyRin
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
public class Main {

	public static void main(String[] args) {
		new RinStream().setPrefix("yyyy/MM/dd HH:mm:ss").setSaveLog(true).setEnableColor(true)
				.setEnableTranslateColor(true).enableError();
		new Main().run();
	}

	private Configuration config;
	private Twitter twitter;

	private HashMap<String, Boolean> stockMap = new HashMap<>();

	public void run() {
		System.out.println("初期化しています...");
		File file = new File("config.yml");
		if (!file.exists()) {
			try {
				file.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}

			Configuration config = new Configuration();
			config.set("Apple.Store-API",
					"https://www.apple.com/jp/shop/fulfillment-messages?pl=true&mt=compact&parts.0=MK2L3J/A&parts.1=MK2K3J/A&searchNearby=true&store=R091");

			config.set("Twitter.Consumer.Key", "KEY");
			config.set("Twitter.Consumer.Secret", "SECRET");
			config.set("Twitter.Access.Token", "TOKEN");
			config.set("Twitter.Access.Secret", "SECRET");

			Config.saveConfig(config, file);
		}

		this.config = Config.getConfig(file);

		System.out.println("Twitter API を読み込んでいます...");
		this.loadTwitter(this.config, file);

		System.out.println("ストアトラッキングを開始します。");

		URL u1 = null;
		try {
			u1 = new URL(this.config.getString("Apple.Store-API"));
		} catch (MalformedURLException e1) {
			e1.printStackTrace();
		}
		final URL url = u1;

		ThreadPool.run(() -> {
			while (true) {
				Configuration config = Config.getConfig(url);
				if (config != null) {
					String status = config.getString("head.status");
					System.out.println("HTTP 応答ステータスコード: " + status);

					List<?> stores = config.getList("body.content.pickupMessage.stores");

					for (Object obj1 : stores) {
						LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) obj1;

						String storeName = (String) map.get("storeName");
						String storeId = (String) map.get("storeNumber");
						String storeEmail = (String) map.get("storeEmail");

						System.out.println("ストア: " + storeName + " (" + storeEmail + ")");

						LinkedHashMap<String, Object> parts = (LinkedHashMap<String, Object>) map.get("partsAvailability");
						for (Object part1 : parts.values()) {
							LinkedHashMap<String, Object> part = (LinkedHashMap<String, Object>) part1;

							String partNumber = (String) part.get("partNumber");
							String itemName = (String) part.get("storePickupProductTitle");
							boolean stock = (boolean) part.get("storeSelectionEnabled");

							System.out.println(
									"- " + partNumber + " (在庫: " + this.formatStockData(stock, true) + "§r), " + itemName);

							String key = storeId + "_" + part;
							Boolean value = this.stockMap.get(key);
							if (value == null) {
								this.stockMap.put(key, stock);
								value = stock;
							}

							if (value != stock) {
								this.stockMap.put(key, stock);

								System.out.println("§r  - 在庫ステータスが変更されました。前回: " + this.formatStockData(value, true)
										+ "§r, 今回: " + this.formatStockData(stock, true) + "§r");
								this.tweetData(itemName + " (" + part + ")", "Apple " + storeName, stock);
							}
						}
						System.out.println("§r");
					}
				}

				try {
					Calendar calendar = Calendar.getInstance();
					calendar.setTime(new Date());
					int h = calendar.get(Calendar.HOUR_OF_DAY);
					int m = calendar.get(Calendar.MINUTE);

					// System.out.println(h);

					if (m >= 50) {
						calendar.set(Calendar.MINUTE, 0);
						calendar.add(Calendar.HOUR_OF_DAY, 1);
					} else if (m >= 40) {
						calendar.set(Calendar.MINUTE, 50);
					} else if (m >= 30) {
						calendar.set(Calendar.MINUTE, 40);
					} else if (m >= 20) {
						calendar.set(Calendar.MINUTE, 30);
					} else if (m >= 10) {
						calendar.set(Calendar.MINUTE, 20);
					} else {
						calendar.set(Calendar.MINUTE, 10);
					}
					calendar.set(Calendar.SECOND, 0);
					calendar.set(Calendar.MILLISECOND, 0);

					Date date = calendar.getTime();
					long sleep = date.getTime() - System.currentTimeMillis();
					System.out.println("処理開始時刻: " + new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(date));
					Thread.sleep(sleep);
				} catch (Exception e) {
				}
			}
		});
	}

	public String formatStockData(boolean bool, boolean color) {
		if (color) {
			return bool ? "§aあり" : "§cなし";
		} else {
			return bool ? "あり" : "なし";
		}
	}

	public void tweetData(String model, String store, boolean available) {
		try {
			this.twitter.updateStatus(
					store + "\n" + model + "\n\n在庫ステータスが変更されました。\n現在の在庫: " + this.formatStockData(available, false));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public JsonElement getJsonFromUrl(String url) {
		try {
			HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
			connection.addRequestProperty("user-agent",
					"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36");

			String result = IOUtils.toString(connection.getInputStream(), StandardCharsets.UTF_8);
			return JsonParser.parseString(result);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public void loadTwitter(Configuration config, File file) {
		if (this.config.getString("Twitter.Consumer.Key").equals("KEY")
				&& this.config.getString("Twitter.Consumer.Secret").equals("SECRET")) {
			System.out.println("Twitter.Consumer.Key と Twitter.Consumer.Secret を config.yml に入力してください！");
			System.exit(0);
		}

		this.twitter = TwitterFactory.getSingleton();
		this.twitter.setOAuthConsumer(this.config.getString("Twitter.Consumer.Key"),
				this.config.getString("Twitter.Consumer.Secret"));

		if (this.config.getString("Twitter.Access.Token").equals("TOKEN")
				&& this.config.getString("Twitter.Access.Secret").equals("SECRET")) {
			RequestToken requestToken;
			try {
				requestToken = this.twitter.getOAuthRequestToken();
			} catch (TwitterException e) {
				return;
			}

			System.out.println("アカウント認証URL: " + requestToken.getAuthorizationURL());
			Scanner scanner = new Scanner(System.in);
			System.out.print("Twitter から提供されたピンを入力してください: ");

			AccessToken accessToken = null;
			try {
				accessToken = this.twitter.getOAuthAccessToken(requestToken, scanner.nextLine());
			} catch (TwitterException e) {
				e.printStackTrace();
				System.exit(0);
			}

			scanner.close();

			this.config.set("Twitter.Access.Token", accessToken.getToken());
			this.config.set("Twitter.Access.Secret", accessToken.getTokenSecret());

			Config.saveConfig(this.config, file);
		}

		this.twitter.setOAuthAccessToken(new AccessToken(this.config.getString("Twitter.Access.Token"),
				this.config.getString("Twitter.Access.Secret")));
		try {
			User user = this.twitter.verifyCredentials();
			System.out.println("アカウント: @" + user.getScreenName());
		} catch (TwitterException e) {
			e.printStackTrace();
			return;
		}
	}

}
