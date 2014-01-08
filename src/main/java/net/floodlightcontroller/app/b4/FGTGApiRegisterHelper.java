package net.floodlightcontroller.app.b4;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.restserver.RestletRoutable;

public class FGTGApiRegisterHelper implements RestletRoutable {

	@Override
	public Restlet getRestlet(Context context) {
		Router router = new Router(context);
        router.attach("/fgtgfile/json", FGTGHandlerResouces.class);
        return router;
	}

	@Override
	public String basePath() {
		return "/wm/fgtgconfig";
	}

}
