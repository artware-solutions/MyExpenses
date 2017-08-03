package org.totschnig.myexpenses.util.ads;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import net.pubnative.mediation.request.PubnativeNetworkRequest;
import net.pubnative.mediation.request.model.PubnativeAdModel;

import org.totschnig.myexpenses.R;

import timber.log.Timber;

public class PubNativeAdHandler extends AdHandler {
  private static final String APP_TOKEN = "d7757800d02945a18bbae190a9a7d4d1";
  private static final String PLACEMENT_NAME = "Banner";
  private final Context context;
  private TextView title;
  private TextView description;
  private ImageView icon;
  private ViewGroup adRoot;
  private Button install;
  private RelativeLayout banner;
  private ViewGroup disclosure;

  public PubNativeAdHandler(ViewGroup adContainer) {
    super(adContainer);
    this.context = adContainer.getContext();
  }

  @Override
  public void init() {
    if (isAdDisabled()) {
      hide();
    } else {
      PubnativeNetworkRequest request = new PubnativeNetworkRequest();
      adRoot = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.pubnative_my_banner, adContainer, false);

      banner = (RelativeLayout) adRoot.findViewById(R.id.pubnative_banner_view);
      title = (TextView) adRoot.findViewById(R.id.pubnative_banner_title);
      description = (TextView) adRoot.findViewById(R.id.pubnative_banner_description);
      icon = (ImageView) adRoot.findViewById(R.id.pubnative_banner_image);
      install = (Button) adRoot.findViewById(R.id.pubnative_banner_button);
      disclosure = (ViewGroup) adRoot.findViewById(R.id.ad_disclosure);

      adContainer.addView(adRoot);

      request.start(context, APP_TOKEN, PLACEMENT_NAME, new PubnativeNetworkRequest.Listener() {

        @Override
        public void onPubnativeNetworkRequestLoaded(PubnativeNetworkRequest request, PubnativeAdModel model) {
          Timber.i("Request loaded, got model: %s", model);
          banner.setVisibility(View.VISIBLE);
          title.setText(model.getTitle());
          description.setText(model.getDescription());
          install.setText(model.getCallToAction());
          icon.setImageBitmap(model.getIcon());
          View sponsorView = model.getAdvertisingDisclosureView(context);
          if (sponsorView != null) {
            disclosure.addView(sponsorView);
          }
          model.withTitle(title)
              .withDescription(description)
              .withIcon(icon)
              .withCallToAction(install)
              .startTracking(context, adRoot);
        }

        @Override
        public void onPubnativeNetworkRequestFailed(PubnativeNetworkRequest request, Exception exception) {
          Timber.e(exception);
          hide();
        }
      });
    }
  }
}
