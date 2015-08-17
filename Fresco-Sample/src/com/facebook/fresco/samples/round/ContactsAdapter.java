package com.facebook.fresco.samples.round;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.fresco.samples.R;

public class ContactsAdapter extends BaseAdapter {
	private Context mContext;
	private LayoutInflater mInflater;
	String uri = "http://h.hiphotos.baidu.com/news/q%3D100/sign=3ca54e9a3c12b31bc16cc929b6193674/6c224f4a20a4462346df77bf9d22720e0df3d7d2.jpg";

	public ContactsAdapter(Context context) {
		mContext = context;
	}

	@Override
	public int getCount() {
		return 3;
	}

	@Override
	public Object getItem(int position) {
		return null;
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public View getView(final int position, View convertView, ViewGroup parent) {
		if (null == mInflater) {
			mInflater = LayoutInflater.from(mContext);
		}
		ViewHolder holder;
		if (convertView == null) {
			holder = new ViewHolder();
			convertView = mInflater.inflate(R.layout.listitem, null);
			holder.draweeview = (SimpleDraweeView) convertView.findViewById(R.id.id_simple_drawee_view1);
			convertView.setTag(holder);
		} else {
			holder = (ViewHolder) convertView.getTag();
		}
		FrescoHelper.displayImageview(holder.draweeview, uri, null, mContext.getResources(), false, 0);
		return convertView;
	}

	public final class ViewHolder {
		public SimpleDraweeView draweeview;
	}

}
