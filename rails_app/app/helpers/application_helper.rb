# Methods added to this helper will be available to all templates in the application.
module ApplicationHelper
  def nav_for(name, path)
    navclass = current_nav(name)
    "<li" + (navclass ? " class=\"#{navclass}\">" : ">") +
      link_to(name, path) +
      "</li>"
  end

  private
    def current_nav(name)
      if @current_section == name
        'current_page_item'
      else
        nil
      end
    end
end
