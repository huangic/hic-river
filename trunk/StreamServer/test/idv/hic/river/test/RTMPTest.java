package idv.hic.river.test;

import org.junit.*;
import org.red5.io.utils.ObjectMap;
import org.red5.server.api.IConnection;
import org.red5.server.api.Red5;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IPendingServiceCallback;
import org.red5.server.net.rtmp.RTMPClient;

public class RTMPTest extends RTMPClient implements IPendingServiceCallback,
		Runnable {

	IConnection conn = null;

	@Test
	public void testGlobalScope() {
		// 測試FUNCTION
		try {
			Thread t = new Thread(this);
			// 這邊一定要讓程序先暫停，否則若連線尚未建立完成，下面的呼叫會讓失作用
			Thread.sleep(5000);
			t.start();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

	@Override
	public void resultReceived(IPendingServiceCall call) {
		System.out.println(String.format("連線ID:%s\t連線結果%s", 
				Red5.getConnectionLocal().hashCode(),call.getResult())
				
		);

	}

	@Before
	public void init() {
		
		// Assert.assertEquals(1,"1");
		// RTMPClient client=new RTMPClient();

		ObjectMap params = new ObjectMap();
		params.put("app", "/Application/");
		params.put("tcUrl", "rtmp://127.0.0.1/Application");

		super.connect("127.0.0.1", 1935, params, this);

	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
		// Red5.setConnectionLocal(conn);
		this.invoke("testApplication", null, this);
		this.disconnect();
	}

}
