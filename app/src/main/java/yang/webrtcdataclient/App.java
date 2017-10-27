package yang.webrtcdataclient;

import android.app.Application;

import org.webrtc.PeerConnectionFactory;

/**
 * author: Matthew Yang on 17/10/26
 * e-mail: yangtian@yy.com
 */

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PeerConnectionFactory.initialize(PeerConnectionFactory
                .InitializationOptions
                .builder(this)
                .createInitializationOptions());
    }
}
