/*
 * This file provided by Facebook is for non-commercial testing and evaluation
 * purposes only.  Facebook reserves all rights not expressly granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * FACEBOOK BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.facebook.fresco.samples.round;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.fresco.samples.R;

public class MainActivity extends Activity {

	private static final Uri URI = Uri
			.parse("http://h.hiphotos.baidu.com/news/q%3D100/sign=3ca54e9a3c12b31bc16cc929b6193674/6c224f4a20a4462346df77bf9d22720e0df3d7d2.jpg");
	private static final int WIDTH = 400;
	private static final int HEIGHT = 240;
	private static final float FOCUS_X = 0.454f;
	private static final float FOCUS_Y = 0.266f;
	private static final int RADIUS = 50;

	private LinearLayout mUnroundedColumn;
	private LinearLayout mRoundedColumn;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Fresco.initialize(this);
		setContentView(R.layout.activity_main);
		ListView listview = (ListView) findViewById(R.id.listview);
		ContactsAdapter adapter = new ContactsAdapter(getBaseContext());
		listview.setAdapter(adapter);
	}
}
