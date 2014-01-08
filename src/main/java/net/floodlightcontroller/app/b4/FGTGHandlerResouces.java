package net.floodlightcontroller.app.b4;

import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FGTGHandlerResouces extends ServerResource {
	
	protected static Logger log = LoggerFactory.getLogger(FGTGHandlerResouces.class);
	
	@Put
	@Post
	public void setConfigFile(String filepath) {
		IFGTGConfigService fgtgservice = (IFGTGConfigService)getContext().getAttributes().get(IFGTGConfigService.class.getCanonicalName());
		fgtgservice.setConfigFile(filepath);
	}
	
}
