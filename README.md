# README
## Generated data sets

The following data sets are generated for the `dev` variant, to be used for the BI workload.

If you are looking for data sets to implement the Interactive workload, please consult the `stable` branch or reach out to us.

{% for file in site.static_files %}
  {% if file.extname == ".zip" -%}
    * [{{ file.path }}]({{ site.baseurl }}{{ file.path }})
  {%- endif %}
{% endfor %}
