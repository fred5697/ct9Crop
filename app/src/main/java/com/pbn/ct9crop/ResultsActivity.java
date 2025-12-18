package com.pbn.ct9crop;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import java.util.ArrayList;
import java.util.List;

public class ResultsActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    private Button btnPrevious, btnNext, btnClose;
    private TextView pageIndicator;
    private List<PageData> pages;

    public static class PageData {
        String title;
        String content;
        int score;
        boolean hasScore;

        public PageData(String title, String content) {
            this.title = title;
            this.content = content;
            this.score = -1;
            this.hasScore = false;
        }

        public PageData(String title, String content, int score) {
            this.title = title;
            this.content = content;
            this.score = score;
            this.hasScore = true;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_results);

        viewPager = findViewById(R.id.viewPager);
        btnPrevious = findViewById(R.id.btnPrevious);
        btnNext = findViewById(R.id.btnNext);
        btnClose = findViewById(R.id.btnClose);
        pageIndicator = findViewById(R.id.pageIndicator);

        // Get data from intent
        String page1Title = getIntent().getStringExtra("page1_title");
        String page1Content = getIntent().getStringExtra("page1_content");
        int page1Score = getIntent().getIntExtra("page1_score", -1);
        String page2Title = getIntent().getStringExtra("page2_title");
        String page2Content = getIntent().getStringExtra("page2_content");
        String page3Title = getIntent().getStringExtra("page3_title");
        String page3Content = getIntent().getStringExtra("page3_content");
        String page4Title = getIntent().getStringExtra("page4_title");
        String page4Content = getIntent().getStringExtra("page4_content");

        pages = new ArrayList<>();
        // Only add non-empty pages
        if (page1Title != null && !page1Title.isEmpty() && page1Content != null && !page1Content.isEmpty()) {
            if (page1Score >= 0) {
                pages.add(new PageData(page1Title, page1Content, page1Score));
            } else {
                pages.add(new PageData(page1Title, page1Content));
            }
        }
        if (page2Title != null && !page2Title.isEmpty() && page2Content != null && !page2Content.isEmpty()) {
            pages.add(new PageData(page2Title, page2Content));
        }
        if (page3Title != null && !page3Title.isEmpty() && page3Content != null && !page3Content.isEmpty()) {
            pages.add(new PageData(page3Title, page3Content));
        }
        if (page4Title != null && !page4Title.isEmpty() && page4Content != null && !page4Content.isEmpty()) {
            pages.add(new PageData(page4Title, page4Content));
        }

        // Fallback in case no pages were added
        if (pages.isEmpty()) {
            pages.add(new PageData("No Data", "No results available"));
        }

        ResultsPagerAdapter adapter = new ResultsPagerAdapter(pages);
        viewPager.setAdapter(adapter);

        updatePageIndicator(0);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updatePageIndicator(position);
                updateButtons(position);
            }
        });

        btnPrevious.setOnClickListener(v -> {
            int currentItem = viewPager.getCurrentItem();
            if (currentItem > 0) {
                viewPager.setCurrentItem(currentItem - 1);
            }
        });

        btnNext.setOnClickListener(v -> {
            int currentItem = viewPager.getCurrentItem();
            if (currentItem < pages.size() - 1) {
                viewPager.setCurrentItem(currentItem + 1);
            }
        });

        btnClose.setOnClickListener(v -> finish());

        updateButtons(0);
    }

    private void updatePageIndicator(int position) {
        if (pages.size() == 1) {
            pageIndicator.setText("Results");
        } else {
            pageIndicator.setText(String.format("Page %d of %d", position + 1, pages.size()));
        }
    }

    private void updateButtons(int position) {
        btnPrevious.setEnabled(position > 0);
        btnNext.setEnabled(position < pages.size() - 1);

        // Hide navigation buttons if only one page
        if (pages.size() == 1) {
            btnPrevious.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);
        } else {
            btnPrevious.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
        }
    }

    private static class ResultsPagerAdapter extends RecyclerView.Adapter<ResultsViewHolder> {
        private final List<PageData> pages;

        public ResultsPagerAdapter(List<PageData> pages) {
            this.pages = pages;
        }

        @NonNull
        @Override
        public ResultsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.page_result, parent, false);
            return new ResultsViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ResultsViewHolder holder, int position) {
            PageData page = pages.get(position);
            holder.pageTitle.setText(page.title);
            holder.pageContent.setText(page.content);

            // Show and color the score indicator if score is available
            if (page.hasScore) {
                holder.scoreIndicator.setVisibility(View.VISIBLE);
                holder.scoreValue.setVisibility(View.VISIBLE);
                holder.scoreValue.setText(String.format("%d/100", page.score));

                int color;
                if (page.score > 90) {
                    color = 0xFF00FF00; // Green
                } else if (page.score >= 80) {
                    color = 0xFFFFFF00; // Yellow
                } else {
                    color = 0xFFFF0000; // Red
                }
                holder.scoreIndicator.getBackground().setTint(color);
            } else {
                holder.scoreIndicator.setVisibility(View.GONE);
                holder.scoreValue.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }
    }

    private static class ResultsViewHolder extends RecyclerView.ViewHolder {
        TextView pageTitle;
        TextView pageContent;
        View scoreIndicator;
        TextView scoreValue;

        public ResultsViewHolder(@NonNull View itemView) {
            super(itemView);
            pageTitle = itemView.findViewById(R.id.pageTitle);
            pageContent = itemView.findViewById(R.id.pageContent);
            scoreIndicator = itemView.findViewById(R.id.scoreIndicator);
            scoreValue = itemView.findViewById(R.id.scoreValue);
        }
    }
}

