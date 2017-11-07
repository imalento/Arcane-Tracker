package net.mbonnin.arcanetracker.hsreplay;

import android.os.Build;
import android.widget.Toast;

import com.google.gson.Gson;

import net.mbonnin.arcanetracker.ArcaneTrackerApplication;
import net.mbonnin.arcanetracker.BuildConfig;
import net.mbonnin.arcanetracker.Lce;
import net.mbonnin.arcanetracker.MainViewCompanion;
import net.mbonnin.arcanetracker.PaperDb;
import net.mbonnin.arcanetracker.R;
import net.mbonnin.arcanetracker.Settings;
import net.mbonnin.arcanetracker.Utils;
import net.mbonnin.arcanetracker.hsreplay.model.Token;
import net.mbonnin.arcanetracker.hsreplay.model.TokenRequest;
import net.mbonnin.arcanetracker.hsreplay.model.UploadRequest;
import net.mbonnin.arcanetracker.model.GameSummary;
import net.mbonnin.arcanetracker.parser.Game;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import timber.log.Timber;

/**
 * Created by martin on 11/29/16.
 */

public class HSReplay {
    private static final java.lang.String KEY_GAME_LIST = "KEY_GAME_LIST";
    private static HSReplay sHSReplay;
    private final OkHttpClient mS3Client;
    private final String mUserAgent;
    private String mToken;
    private ArrayList<GameSummary> mGameList;
    private Service mService;

    public static HSReplay get() {
        if (sHSReplay == null) {
            sHSReplay = new HSReplay();
        }

        return sHSReplay;
    }

    public ArrayList<GameSummary> getGameSummary() {
        return mGameList;
    }

    public void doUploadGame(String matchStart, String friendlyPlayerId, Game game, GameSummary summary, String gameStr) {
        Timber.w("doUploadGame");

        UploadRequest uploadRequest = new UploadRequest();
        uploadRequest.match_start = matchStart;
        uploadRequest.build = 20022;
        uploadRequest.friendly_player_id = friendlyPlayerId;
        uploadRequest.game_type = summary.bnetGameType;
        if (game.rank > 0) {
            if (friendlyPlayerId.equals("1")) {
                uploadRequest.player1.rank = game.rank;
            } else {
                uploadRequest.player2.rank = game.rank;
            }
        }

        service().createUpload("https://upload.hsreplay.net/api/v1/replay/upload/request", uploadRequest)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap(upload -> {
                    Timber.w("url is " + upload.url);
                    Timber.w("put_url is " + upload.put_url);

                    if (upload.put_url == null) {
                        return Observable.error(new Exception("no put_url"));
                    }

                    summary.hsreplayUrl = upload.url;
                    PaperDb.INSTANCE.write(KEY_GAME_LIST, mGameList);

                    return putToS3(upload.put_url, gameStr).subscribeOn(Schedulers.io());
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(unused -> {
                    Timber.d("hsreplay upload success");
                    Toast.makeText(ArcaneTrackerApplication.getContext(), ArcaneTrackerApplication.getContext().getString(R.string.hsreplaySuccess), Toast.LENGTH_LONG).show();
                }, error -> {
                    Timber.e(error);
                    Toast.makeText(ArcaneTrackerApplication.getContext(), ArcaneTrackerApplication.getContext().getString(R.string.hsreplayError), Toast.LENGTH_LONG).show();
                });
    }

    public void uploadGame(String matchStart, Game game, String gameStr) {
        boolean hsReplayEnabled = HSReplay.get().token() != null;

        Timber.w("uploadGame [game=%s] [hsReplayEnabled=%b] [token=%s]", game, hsReplayEnabled, mToken);
        if (game == null) {
            return;
        }

        GameSummary summary = new GameSummary();
        summary.coin = game.getPlayer().hasCoin;
        summary.win = game.victory;
        summary.hero = game.player.classIndex();
        summary.opponentHero = game.opponent.classIndex();
        summary.date = Utils.INSTANCE.getISO8601DATEFORMAT().format(new Date());
        summary.deckName = MainViewCompanion.getPlayerCompanion().getDeck().name;
        summary.bnetGameType = game.bnetGameType.getIntValue();

        mGameList.add(0, summary);
        PaperDb.INSTANCE.write(KEY_GAME_LIST, mGameList);

        if (mToken == null) {
            return;
        }

        if (hsReplayEnabled) {
            doUploadGame(matchStart, game.player.entity.PlayerID, game, summary, gameStr);
        }
    }

    public Observable<Void> putToS3(String putUrl, String gameStr) {
        return Observable.fromCallable(() -> {
            RequestBody body = RequestBody.create(null, gameStr);
            Request request = new Request.Builder()
                    .put(body)
                    .url(putUrl)
                    .header("Content-Type", "text/plain")
                    .addHeader("User-Agent", mUserAgent)
                    .build();

            try {
                Response response = mS3Client.newCall(request).execute();
                if (!response.isSuccessful()) {
                    return null;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        });
    }

    public HSReplay() {
        mGameList = PaperDb.INSTANCE.read(KEY_GAME_LIST);
        if (mGameList == null) {
            mGameList = new ArrayList<>();
        }

        mUserAgent = ArcaneTrackerApplication.getContext().getPackageName() + "/" + BuildConfig.VERSION_NAME
                + "; Android " + Build.VERSION.RELEASE + ";";

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();

                    Request.Builder requestBuilder = request.newBuilder();
                    requestBuilder.addHeader("X-Api-Key", "8b27e53b-0256-4ff1-b134-f531009c05a3");
                    requestBuilder.addHeader("User-Agent", mUserAgent);
                    if (mToken != null) {
                        requestBuilder.addHeader("Authorization", "Token " + mToken);
                    }
                    request = requestBuilder.build();

                    return chain.proceed(request);
                }).build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://hsreplay.net/api/v1/")
                .addCallAdapterFactory(RxJavaCallAdapterFactory.createWithScheduler(Schedulers.io()))
                .addConverterFactory(GsonConverterFactory.create(new Gson()))
                .client(client)
                .build();

        mService = retrofit.create(Service.class);

        mS3Client = new OkHttpClient.Builder()
                //.addInterceptor(new GzipRequestInterceptor())
                .build();

        mToken = Settings.get(Settings.HSREPLAY_TOKEN, null);
        Timber.w("init token=" + mToken);

        //doUploadGame(Utils.ISO8601DATEFORMAT.format(new Date()), "1", null, "toto");
    }

    public Service service() {
        return mService;
    }

    public String token() {
        return mToken;
    }

    public Observable<Lce<String>> createToken() {
        TokenRequest tokenRequest = new TokenRequest();
        tokenRequest.test_data = Utils.INSTANCE.isAppDebuggable();
        return service().createToken(tokenRequest)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(token -> {
                    if (token.key == null) {
                        throw new RuntimeException("null key");
                    }
                    mToken = token.key;
                    Settings.set(Settings.HSREPLAY_TOKEN, token.key);
                    return Lce.data(mToken);
                }).onErrorReturn(Lce::error)
                .startWith(Lce.loading());
    }

    public Observable<Lce<String>> getClaimUrl() {
        return service().createClaim()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(claimResult -> Lce.data(claimResult.full_url))
                .startWith(Lce.loading())
                .onErrorReturn(Lce::error);
    }

    public Observable<Lce<Token>> getUser() {
        return service().getToken(mToken)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(Lce::data)
                .onErrorReturn(Lce::error)
                .startWith(Lce.loading());
    }

    public void unlink() {
        mToken = null;
        Settings.set(Settings.HSREPLAY_TOKEN, null);
    }

    public void eraseGameSummary() {
        mGameList.clear();
        PaperDb.INSTANCE.write(KEY_GAME_LIST, mGameList);
    }
}
